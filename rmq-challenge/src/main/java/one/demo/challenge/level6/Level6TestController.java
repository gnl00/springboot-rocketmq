package one.demo.challenge.level6;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/challenge/level6")
public class Level6TestController {

    @Autowired
    private L6OrderService l6OrderService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private PointsService pointsService;

    @Autowired(required = false)
    private Level6ProducerBuggy level6ProducerBuggy;

    @Autowired(required = false)
    private Level6ProducerFixed level6ProducerFixed;

    /**
     * 创建订单（统一入口）
     *
     * @param userId 用户ID（默认：USER-001）
     * @param productId 商品ID（默认：PRODUCT-001）
     * @param quantity 数量（默认：5）
     * @param amount 金额（默认：100.00）
     * @param version 版本选择：buggy1, buggy2, buggy3, fixed（默认：buggy1）
     */
    @GetMapping("/createOrder")
    public String createOrder(
            @RequestParam(defaultValue = "USER-001") String userId,
            @RequestParam(defaultValue = "PRODUCT-001") String productId,
            @RequestParam(defaultValue = "5") Integer quantity,
            @RequestParam(defaultValue = "100.00") BigDecimal amount,
            @RequestParam(defaultValue = "buggy1") String version) {

        log.info("📝 创建订单请求 - UserId: {}, ProductId: {}, Quantity: {}, Amount: {}, Version: {}",
                userId, productId, quantity, amount, version);

        try {
            String result;
            switch (version.toLowerCase()) {
                case "buggy1":
                    if (level6ProducerBuggy == null) {
                        return "❌ Buggy 版本未启用，请检查配置";
                    }
                    result = level6ProducerBuggy.createOrderApproach1(userId, productId, quantity, amount);
                    break;

                case "buggy2":
                    if (level6ProducerBuggy == null) {
                        return "❌ Buggy 版本未启用，请检查配置";
                    }
                    result = level6ProducerBuggy.createOrderApproach2(userId, productId, quantity, amount);
                    break;

                case "buggy3":
                    if (level6ProducerBuggy == null) {
                        return "❌ Buggy 版本未启用，请检查配置";
                    }
                    result = level6ProducerBuggy.createOrderApproach3(userId, productId, quantity, amount);
                    break;

                case "fixed":
                    if (level6ProducerFixed == null) {
                        return "❌ Fixed 版本未启用，请检查配置";
                    }
                    result = level6ProducerFixed.createOrder(userId, productId, quantity, amount);
                    break;

                default:
                    return String.format("""
                            ❌ 未知的版本: %s

                            支持的版本：
                            - buggy1: 先创建订单，再发送消息
                            - buggy2: 先发送消息，再创建订单
                            - buggy3: 使用try-catch回滚
                            - fixed: 使用事务消息（推荐）

                            示例：
                            curl "http://localhost:8070/challenge/level6/createOrder?version=fixed"
                            """, version);
            }

            return result + "\n\n" + getQuickCheckTip();

        } catch (Exception e) {
            log.error("❌ 创建订单失败", e);
            return String.format("❌ 创建订单失败: %s\n\n%s", e.getMessage(), getQuickCheckTip());
        }
    }

    /**
     * 快速测试接口（使用默认参数）
     */
    @GetMapping("/quickTest")
    public String quickTest(@RequestParam(defaultValue = "buggy1") String version) {
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
                💡 快速检查数据一致性：
                curl "http://localhost:8070/challenge/level6/checkAll"

                💡 查看帮助信息：
                curl "http://localhost:8070/challenge/level6/help"
                """;
    }

    /**
     * 查询订单详情
     */
    @GetMapping("/checkOrder")
    public String checkOrder(@RequestParam String orderId) {
        L6Order l6Order = l6OrderService.getOrder(orderId);

        if (l6Order == null) {
            return String.format("❌ 订单不存在 - OrderId: %s\n\n" +
                    "⚠️ 这可能是数据不一致的表现：消息已发送，但订单创建失败！", orderId);
        }

        Integer inventory = inventoryService.getInventory(l6Order.getProductId());
        Integer points = pointsService.getPoints(l6Order.getUserId());

        return String.format("""
                📊 订单详情

                订单信息：
                - OrderId: %s
                - UserId: %s
                - ProductId: %s
                - Quantity: %d
                - Amount: %.2f
                - State: %s

                关联数据：
                - 当前库存: %d
                - 用户积分: %d

                💡 检查数据一致性：
                - 如果订单状态是 PENDING，库存应该已扣减，积分应该已增加
                - 如果订单状态是 CANCELLED，库存应该已恢复，积分应该已扣减
                """,
                l6Order.getOrderId(),
                l6Order.getUserId(),
                l6Order.getProductId(),
                l6Order.getQuantity(),
                l6Order.getAmount(),
                l6Order.getState().getDescription(),
                inventory,
                points);
    }

    /**
     * 查询所有数据
     */
    @GetMapping("/checkAll")
    public String checkAll() {
        Map<String, L6Order> orders = l6OrderService.getAllOrders();
        Map<String, Integer> inventory = inventoryService.getAllInventory();
        Map<String, Integer> points = pointsService.getAllPoints();

        StringBuilder sb = new StringBuilder();
        sb.append("📊 系统数据总览\n\n");

        sb.append("订单列表：\n");
        if (orders.isEmpty()) {
            sb.append("  (无订单)\n");
        } else {
            orders.forEach((orderId, order) -> {
                sb.append(String.format("  - %s: %s, %s, 数量=%d, 金额=%.2f, 状态=%s\n",
                        orderId, order.getUserId(), order.getProductId(),
                        order.getQuantity(), order.getAmount(), order.getState().getDescription()));
            });
        }

        sb.append("\n库存列表：\n");
        inventory.forEach((productId, stock) -> {
            sb.append(String.format("  - %s: %d\n", productId, stock));
        });

        sb.append("\n积分列表：\n");
        points.forEach((userId, point) -> {
            sb.append(String.format("  - %s: %d\n", userId, point));
        });

        return sb.toString();
    }

    /**
     * 重置所有数据
     */
    @GetMapping("/reset")
    public String reset() {
        l6OrderService.reset();
        inventoryService.reset();
        pointsService.reset();

        return """
                ✅ 所有数据已重置

                初始状态：
                - 订单: 0 个
                - 库存: PRODUCT-001=100, PRODUCT-002=50, PRODUCT-003=200
                - 积分: USER-001=0, USER-002=0, USER-003=0
                """;
    }

    /**
     * 帮助信息
     */
    @GetMapping("/help")
    public String help() {
        return """
                🎯 Level 6 挑战：事务消息问题

                ## 问题场景

                用户下单后，需要完成三个操作：
                1. 创建订单（本地数据库）
                2. 扣减库存（下游服务，通过MQ通知）
                3. 增加积分（下游服务，通过MQ通知）

                这三个操作必须保持一致性：要么全部成功，要么全部失败。

                ## 快速测试（推荐）

                ### 1. 重置数据
                curl "http://localhost:8070/challenge/level6/reset"

                ### 2. 测试 Buggy 版本（方案1）
                curl "http://localhost:8070/challenge/level6/createOrder?version=buggy1"

                ### 3. 检查数据一致性
                curl "http://localhost:8070/challenge/level6/checkAll"

                ### 4. 测试 Fixed 版本（事务消息）
                curl "http://localhost:8070/challenge/level6/reset"
                curl "http://localhost:8070/challenge/level6/createOrder?version=fixed"
                curl "http://localhost:8070/challenge/level6/checkAll"

                ## 详细测试

                ### 方案1：先创建订单，再发送消息
                curl "http://localhost:8070/challenge/level6/createOrder?version=buggy1"
                问题：如果消息发送失败，订单已创建，但库存和积分未变化

                ### 方案2：先发送消息，再创建订单
                curl "http://localhost:8070/challenge/level6/createOrder?version=buggy2"
                问题：如果订单创建失败，消息已发送，库存和积分已变化，但订单不存在

                ### 方案3：使用try-catch回滚
                curl "http://localhost:8070/challenge/level6/createOrder?version=buggy3"
                问题：回滚操作本身可能失败，且中间状态可能被观察到

                ### Fixed版本：使用事务消息
                curl "http://localhost:8070/challenge/level6/createOrder?version=fixed"
                优势：保证本地事务和消息发送的最终一致性

                ## 自定义参数测试

                curl "http://localhost:8070/challenge/level6/createOrder?userId=USER-002&productId=PRODUCT-002&quantity=10&amount=200.00&version=fixed"

                ## 模拟故障场景

                ### 模拟消息发送失败
                curl "http://localhost:8070/challenge/level6/buggy/simulateMessageFailure?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

                ### 模拟订单创建失败
                curl "http://localhost:8070/challenge/level6/buggy/simulateOrderFailure?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

                ### 模拟本地事务失败（Fixed版本）
                curl "http://localhost:8070/challenge/level6/fixed/simulateLocalTransactionFailure?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

                ## 问题分析

                核心问题：本地事务和消息发送不是原子操作

                - 先创建订单，再发送消息 → 消息发送失败时，订单已创建
                - 先发送消息，再创建订单 → 订单创建失败时，消息已发送
                - 使用try-catch回滚 → 回滚可能失败，且无法保证原子性

                ## 解决方案：RocketMQ 事务消息

                1. 发送Half消息（对消费者不可见）
                2. 执行本地事务（创建订单）
                3. 根据本地事务结果，Commit或Rollback消息
                4. 如果长时间未收到确认，Broker会回查事务状态

                ## 其他接口

                - 查看所有数据：curl "http://localhost:8070/challenge/level6/checkAll"
                - 查看订单详情：curl "http://localhost:8070/challenge/level6/checkOrder?orderId=ORDER-xxx"
                - 重置数据：curl "http://localhost:8070/challenge/level6/reset"
                - 快速测试：curl "http://localhost:8070/challenge/level6/quickTest?version=fixed"

                准备好了吗？开始你的挑战！🚀
                """;
    }
}
