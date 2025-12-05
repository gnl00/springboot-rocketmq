package one.demo.challenge.level7;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Level 7 挑战：延时消息与定时任务（Buggy 版本）
 *
 * 问题场景：
 * 用户下单后，需要在 30 分钟内完成支付，否则订单自动取消并恢复库存。
 *
 * 问题现象：
 * 1. 延时消息发送失败，订单永远不会被取消（僵尸订单）
 * 2. 用户支付后，延时消息仍然执行，订单被错误取消
 * 3. RocketMQ 只支持 18 个固定的延时等级，无法精确设置 30 分钟
 * 4. 延时消息重复消费，订单被多次取消，库存被多次恢复
 *
 * Bug 分析：
 * 1. 没有处理延时消息发送失败的情况
 * 2. 用户支付后，无法取消已发送的延时消息
 * 3. 延时时间只能选择 20m 或 30m，无法精确到 30 分钟
 * 4. 消费者没有检查订单状态，直接取消订单
 * 5. 没有幂等性保证，重复消费会导致库存多次恢复
 *
 * 任务：
 * 1. 运行测试，观察问题现象
 * 2. 分析为什么会出现这些问题
 * 3. 设计并实现解决方案
 *
 * 提示：
 * - RocketMQ 延时等级：1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h
 * - 延时等级 16 = 30m，这是最接近 30 分钟的选项
 * - 需要在消费者端检查订单状态，避免错误取消
 * - 需要实现幂等性，避免重复取消
 */
@Slf4j
@RestController
@RequestMapping("/challenge/level7/buggy")
public class Level7ProducerBuggy {

    private static final String ENDPOINTS = "localhost:8081";
    private static final String TOPIC = "order-cancel-topic";

    @Autowired
    private OrderService orderService;

    @Autowired
    private InventoryService inventoryService;

    private Producer producer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() throws ClientException {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(ENDPOINTS)
                .setRequestTimeout(Duration.ofSeconds(3))
                .build();

        this.producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics(TOPIC)
                .build();

        log.info("✅ Level 7 Producer (Buggy) 初始化完成");
    }

    @PreDestroy
    public void destroy() {
        if (producer != null) {
            try {
                producer.close();
            } catch (Exception e) {
                log.error("关闭 Producer 失败", e);
            }
        }
    }

    /**
     * 创建订单（Buggy 版本）
     * Bug: 延时消息发送失败，订单永远不会被取消
     */
    @GetMapping("/createOrder")
    public String createOrder(
            @RequestParam String userId,
            @RequestParam String productId,
            @RequestParam Integer quantity,
            @RequestParam BigDecimal amount) {

        String orderId = "ORDER-" + UUID.randomUUID().toString().substring(0, 8);

        try {
            // 步骤1: 扣减库存
            boolean success = inventoryService.deductInventory(productId, quantity);
            if (!success) {
                return "❌ 库存不足，下单失败";
            }

            // 步骤2: 创建订单
            Order order = new Order(orderId, userId, productId, quantity, amount);
            orderService.createOrder(order);
            log.info("✅ [Buggy] 订单创建成功 - OrderId: {}", orderId);

            // 步骤3: 发送延时消息（30分钟后取消订单）
            // Bug: 如果这里发送失败，订单永远不会被取消
            sendDelayMessage(orderId);
            log.info("✅ [Buggy] 延时消息已发送 - OrderId: {}", orderId);

            return String.format("""
                    ✅ 订单创建成功 - OrderId: %s

                    订单信息：
                    - UserId: %s
                    - ProductId: %s
                    - Quantity: %d
                    - Amount: %.2f
                    - 过期时间: 30 分钟后

                    ⚠️ Bug 提示：
                    1. 如果延时消息发送失败，订单永远不会被取消
                    2. 用户支付后，延时消息仍然会执行
                    3. RocketMQ 只支持固定的延时等级（20m 或 30m）

                    💡 测试建议：
                    - 等待 30 秒后查看订单状态（测试环境延时时间缩短）
                    - 在延时消息执行前支付订单，观察是否被错误取消
                    """, orderId, userId, productId, quantity, amount);

        } catch (Exception e) {
            log.error("❌ [Buggy] 订单创建失败", e);
            // Bug: 异常处理不完善，库存可能已扣减但订单未创建
            return "❌ 订单创建失败: " + e.getMessage();
        }
    }

    /**
     * 发送延时消息
     * Bug: 使用固定的延时等级，无法精确控制延时时间
     */
    private void sendDelayMessage(String orderId) throws Exception {
        OrderCancelEvent event = new OrderCancelEvent(orderId, "TIMEOUT");
        String messageBody = objectMapper.writeValueAsString(event);

        ClientServiceProvider provider = ClientServiceProvider.loadService();

        // Bug 1: 使用延时等级 16 (30m)，但实际需求可能是精确的 30 分钟
        // Bug 2: 没有处理发送失败的情况
        // Bug 3: 用户支付后，无法取消这个延时消息
        Message message = provider.newMessageBuilder()
                .setTopic(TOPIC)
                .setTag("order-cancel")
                .setKeys(orderId)
                .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
                .setDeliveryTimestamp(System.currentTimeMillis() + 35 * 1000) // 延迟 30 秒（测试用）
                .build();

        SendReceipt receipt = producer.send(message);
        log.info("📤 [Buggy] 延时消息已发送 - OrderId: {}, MessageId: {}, 延时: 30秒",
                orderId, receipt.getMessageId());
    }

    /**
     * 模拟延时消息发送失败
     */
    @GetMapping("/simulateDelayMessageFailure")
    public String simulateDelayMessageFailure(
            @RequestParam String userId,
            @RequestParam String productId,
            @RequestParam Integer quantity,
            @RequestParam BigDecimal amount) {

        String orderId = "ORDER-" + UUID.randomUUID().toString().substring(0, 8);

        try {
            // 扣减库存
            inventoryService.deductInventory(productId, quantity);

            // 创建订单
            Order order = new Order(orderId, userId, productId, quantity, amount);
            orderService.createOrder(order);
            log.info("✅ 订单创建成功 - OrderId: {}", orderId);

            // 模拟延时消息发送失败
            log.error("❌ 模拟延时消息发送失败 - OrderId: {}", orderId);
            throw new RuntimeException("模拟网络异常：连接 Broker 超时");

        } catch (Exception e) {
            return String.format("""
                    ❌ 延时消息发送失败 - OrderId: %s

                    🔍 Bug 现象：
                    - 订单已创建（状态：待支付）
                    - 库存已扣减
                    - 但延时消息发送失败
                    - 30 分钟后订单不会被自动取消
                    - 形成"僵尸订单"，永久占用库存

                    💡 检查数据：
                    curl "http://localhost:8070/challenge/level7/checkOrder?orderId=%s"
                    curl "http://localhost:8070/challenge/level7/checkAll"

                    ⚠️ 这是一个严重的 Bug！
                    """, orderId, orderId);
        }
    }

    /**
     * 支付订单
     */
    @GetMapping("/payOrder")
    public String payOrder(@RequestParam String orderId) {
        Order order = orderService.getOrder(orderId);
        if (order == null) {
            return "❌ 订单不存在";
        }

        if (order.getStatus() != OrderStatus.PENDING) {
            return "❌ 订单状态不是待支付，无法支付";
        }

        boolean success = orderService.payOrder(orderId);
        if (success) {
            return String.format("""
                    ✅ 订单支付成功 - OrderId: %s

                    ⚠️ Bug 提示：
                    虽然订单已支付，但之前发送的延时消息仍然会在 30 分钟后执行！
                    如果消费者没有检查订单状态，订单可能被错误取消！

                    💡 等待 30 秒后检查订单状态：
                    curl "http://localhost:8070/challenge/level7/checkOrder?orderId=%s"
                    """, orderId, orderId);
        } else {
            return "❌ 订单支付失败";
        }
    }
}
