package one.demo.challenge.level7;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Level 7 测试控制器
 */
@Slf4j
@RestController
@RequestMapping("/challenge/level7")
public class Level7TestController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired(required = false)
    private Level7ProducerBuggy level7ProducerBuggy;

    /**
     * 创建订单（统一入口）
     */
    @GetMapping("/createOrder")
    public String createOrder(
            @RequestParam(defaultValue = "USER-001") String userId,
            @RequestParam(defaultValue = "PRODUCT-001") String productId,
            @RequestParam(defaultValue = "5") Integer quantity,
            @RequestParam(defaultValue = "100.00") BigDecimal amount,
            @RequestParam(defaultValue = "buggy") String version) {

        log.info("📝 创建订单请求 - UserId: {}, ProductId: {}, Quantity: {}, Amount: {}, Version: {}",
                userId, productId, quantity, amount, version);

        try {
            String result;
            switch (version.toLowerCase()) {
                case "buggy":
                    if (level7ProducerBuggy == null) {
                        return "❌ Buggy 版本未启用，请检查配置";
                    }
                    result = level7ProducerBuggy.createOrder(userId, productId, quantity, amount);
                    break;

                case "fixed":
                    return "💡 Fixed 版本等待你来实现！\n\n" +
                            "提示：\n" +
                            "1. 如何处理延时消息发送失败？\n" +
                            "2. 如何在用户支付后取消延时消息？\n" +
                            "3. 如何实现精确的延时时间？\n" +
                            "4. 如何保证幂等性？";

                default:
                    return String.format("""
                            ❌ 未知的版本: %s

                            支持的版本：
                            - buggy: 有问题的实现（默认）
                            - fixed: 你的解决方案（待实现）

                            示例：
                            curl "http://localhost:8070/challenge/level7/createOrder?version=buggy"
                            """, version);
            }

            return result + "\n\n" + getQuickCheckTip();

        } catch (Exception e) {
            log.error("❌ 创建订单失败", e);
            return String.format("❌ 创建订单失败: %s\n\n%s", e.getMessage(), getQuickCheckTip());
        }
    }

    /**
     * 支付订单
     */
    @GetMapping("/payOrder")
    public String payOrder(@RequestParam String orderId) {
        if (level7ProducerBuggy != null) {
            return level7ProducerBuggy.payOrder(orderId);
        }
        return "❌ Producer 未启用";
    }

    /**
     * 查询订单详情
     */
    @GetMapping("/checkOrder")
    public String checkOrder(@RequestParam String orderId) {
        Order order = orderService.getOrder(orderId);

        if (order == null) {
            return String.format("❌ 订单不存在 - OrderId: %s", orderId);
        }

        Integer inventory = inventoryService.getInventory(order.getProductId());

        return String.format("""
                📊 订单详情

                订单信息：
                - OrderId: %s
                - UserId: %s
                - ProductId: %s
                - Quantity: %d
                - Amount: %.2f
                - Status: %s
                - CreateTime: %s
                - ExpireTime: %s

                关联数据：
                - 当前库存: %d

                💡 状态说明：
                - PENDING: 待支付（30分钟后自动取消）
                - PAID: 已支付
                - CANCELLED: 已取消（库存已恢复）
                - EXPIRED: 已过期
                """,
                order.getOrderId(),
                order.getUserId(),
                order.getProductId(),
                order.getQuantity(),
                order.getAmount(),
                order.getStatus().getDescription(),
                order.getCreateTime(),
                order.getExpireTime(),
                inventory);
    }

    /**
     * 查询所有数据
     */
    @GetMapping("/checkAll")
    public String checkAll() {
        Map<String, Order> orders = orderService.getAllOrders();
        Map<String, Integer> inventory = inventoryService.getAllInventory();

        StringBuilder sb = new StringBuilder();
        sb.append("📊 系统数据总览\n\n");

        sb.append("订单列表：\n");
        if (orders.isEmpty()) {
            sb.append("  (无订单)\n");
        } else {
            orders.forEach((orderId, order) -> {
                sb.append(String.format("  - %s: %s, %s, 数量=%d, 金额=%.2f, 状态=%s\n",
                        orderId, order.getUserId(), order.getProductId(),
                        order.getQuantity(), order.getAmount(), order.getStatus().getDescription()));
            });
        }

        sb.append("\n库存列表：\n");
        inventory.forEach((productId, stock) -> {
            sb.append(String.format("  - %s: %d\n", productId, stock));
        });

        return sb.toString();
    }

    /**
     * 重置所有数据
     */
    @GetMapping("/reset")
    public String reset() {
        orderService.reset();
        inventoryService.reset();

        return """
                ✅ 所有数据已重置

                初始状态：
                - 订单: 0 个
                - 库存: PRODUCT-001=100, PRODUCT-002=50, PRODUCT-003=200
                """;
    }

    /**
     * 快速测试
     */
    @GetMapping("/quickTest")
    public String quickTest(@RequestParam(defaultValue = "buggy") String version) {
        return String.format("""
                🚀 快速测试 - 版本: %s

                正在创建订单...
                - UserId: USER-001
                - ProductId: PRODUCT-001
                - Quantity: 5
                - Amount: 100.00

                """, version) + createOrder("USER-001", "PRODUCT-001", 5, new BigDecimal("100.00"), version);
    }

    /**
     * 获取快速检查提示
     */
    private String getQuickCheckTip() {
        return """
                💡 快速检查数据：
                curl "http://localhost:8070/challenge/level7/checkAll"

                💡 查看帮助信息：
                curl "http://localhost:8070/challenge/level7/help"
                """;
    }

    /**
     * 帮助信息
     */
    @GetMapping("/help")
    public String help() {
        return """
                🎯 Level 7 挑战：延时消息与定时任务

                ## 问题场景

                用户下单后，需要在 30 分钟内完成支付，否则订单自动取消并恢复库存。

                ## Buggy 版本的问题

                1. **延时消息发送失败** → 订单永远不会被取消（僵尸订单）
                2. **用户支付后** → 延时消息仍然执行，订单被错误取消
                3. **延时时间不精确** → RocketMQ 只支持固定的 18 个延时等级
                4. **重复消费** → 库存被多次恢复

                ## 快速测试

                ### 1. 测试正常流程（订单超时取消）
                ```bash
                # 重置数据
                curl "http://localhost:8070/challenge/level7/reset"

                # 创建订单
                curl "http://localhost:8070/challenge/level7/createOrder?version=buggy"

                # 等待 30 秒（延时消息执行）
                sleep 30

                # 检查订单状态（应该是已取消）
                curl "http://localhost:8070/challenge/level7/checkAll"
                ```

                ### 2. 测试 Bug：用户支付后订单被错误取消
                ```bash
                # 重置数据
                curl "http://localhost:8070/challenge/level7/reset"

                # 创建订单（返回 OrderId）
                curl "http://localhost:8070/challenge/level7/createOrder?version=buggy"

                # 10 秒后支付订单
                sleep 10
                curl "http://localhost:8070/challenge/level7/payOrder?orderId=ORDER-xxx"

                # 再等待 20 秒（延时消息执行）
                sleep 20

                # 检查订单状态
                curl "http://localhost:8070/challenge/level7/checkOrder?orderId=ORDER-xxx"
                # Bug 现象：订单状态变成"已取消"，但应该是"已支付"
                ```

                ### 3. 测试 Bug：延时消息发送失败
                ```bash
                # 重置数据
                curl "http://localhost:8070/challenge/level7/reset"

                # 模拟延时消息发送失败
                curl "http://localhost:8070/challenge/level7/buggy/simulateDelayMessageFailure?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

                # 等待 30 秒
                sleep 30

                # 检查订单状态
                curl "http://localhost:8070/challenge/level7/checkAll"
                # Bug 现象：订单仍然是"待支付"，永远不会被取消
                ```

                ## 核心问题分析

                ### 问题 1：延时消息发送失败
                ```
                创建订单 ✅ → 发送延时消息 ❌ → 订单永远不会被取消
                ```

                ### 问题 2：无法取消延时消息
                ```
                发送延时消息 ✅ → 用户支付 ✅ → 延时消息仍然执行 ❌
                ```

                ### 问题 3：延时时间不精确
                ```
                RocketMQ 延时等级：1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h
                需求：30 分钟
                只能选择：20m 或 30m（不精确）
                ```

                ## 挑战任务

                1. ✅ 运行 Buggy 版本，观察问题现象
                2. ✅ 理解为什么会出现这些问题
                3. 🔧 设计并实现 Fixed 版本
                4. 🔧 考虑以下解决方案：
                   - 如何处理延时消息发送失败？
                   - 如何在用户支付后取消延时消息？
                   - 如何实现精确的延时时间？
                   - 如何保证幂等性？

                ## 解决方案提示

                ### 方案 1：定时扫描数据库
                - 优点：可以精确控制延时时间，可以取消
                - 缺点：数据库压力大，实时性差

                ### 方案 2：时间轮算法
                - 优点：性能好，可以精确控制，可以取消
                - 缺点：内存占用，单机方案

                ### 方案 3：RocketMQ + 时间轮
                - 优点：消息持久化，分布式友好，可以取消
                - 缺点：实现复杂度较高

                ## 其他接口

                - 创建订单：curl "http://localhost:8070/challenge/level7/createOrder?version=buggy"
                - 支付订单：curl "http://localhost:8070/challenge/level7/payOrder?orderId=ORDER-xxx"
                - 查看订单：curl "http://localhost:8070/challenge/level7/checkOrder?orderId=ORDER-xxx"
                - 查看所有数据：curl "http://localhost:8070/challenge/level7/checkAll"
                - 重置数据：curl "http://localhost:8070/challenge/level7/reset"
                - 快速测试：curl "http://localhost:8070/challenge/level7/quickTest?version=buggy"

                准备好了吗？开始你的挑战！🚀
                """;
    }
}
