package one.demo.challenge.level5;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Level 5 挑战：消息顺序性问题
 *
 * 问题场景：
 * 订单状态必须按照特定顺序流转：创建 → 支付 → 发货 → 完成
 * 但是由于消息发送和消费的无序性，导致状态更新混乱。
 *
 * 问题现象：
 * 1. 订单还未支付就显示已发货
 * 2. 订单还未创建就收到支付消息
 * 3. 状态转换混乱，业务逻辑错误
 * 4. 数据库中订单状态不一致
 *
 * Bug 分析：
 * 1. 生产者：使用普通消息发送，没有指定分区/队列
 * 2. 消费者：并发消费，多个线程同时处理同一订单的不同消息
 * 3. 结果：消息到达和处理顺序无法保证
 *
 * 任务：
 * 1. 运行测试，观察订单状态混乱的现象
 * 2. 分析为什么会出现乱序
 * 3. 提出并实现解决方案
 *
 * 提示：
 * - RocketMQ 的普通消息不保证顺序
 * - 并发消费会导致乱序
 * - 需要使用顺序消息和顺序消费
 */
@Slf4j
@RestController
@RequestMapping("/challenge/level5")
public class Level5ProducerFixed {

    private static final String ENDPOINTS = "localhost:8080";
    private static final String TOPIC = "order-status-topic";

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
                .build();

        log.info("Level 5 Producer (Fixed) 初始化完成");
    }

    /**
     * 模拟订单状态流转
     * Fixed: 使用 MessageGroup 保证 FIFO 顺序
     *
     * @param orderId 订单ID
     */
    @GetMapping("/simulateOrderFlow")
    public String simulateOrderFlow(@RequestParam(defaultValue = "ORDER-001") String orderId) {
        try {
            log.info("🚀 开始订单流转 - OrderId: {}", orderId);

            // 按顺序发送订单状态变更消息
            // 1. 订单创建
            sendStatusChange(orderId, OrderStatus.CREATED, 1);
            TimeUnit.MILLISECONDS.sleep(100);

            // 2. 支付
            sendStatusChange(orderId, OrderStatus.PAID, 2);
            TimeUnit.MILLISECONDS.sleep(100);

            // 3. 发货
            sendStatusChange(orderId, OrderStatus.SHIPPED, 3);
            TimeUnit.MILLISECONDS.sleep(100);

            // 4. 完成
            sendStatusChange(orderId, OrderStatus.COMPLETED, 4);

            return String.format("✅ 订单 %s 状态流转消息已发送", orderId);

        } catch (Exception e) {
            log.error("模拟订单流转失败", e);
            return "❌ 发送失败: " + e.getMessage();
        }
    }

    private static int incrementId = 1;
    /**
     * 并发模拟多个订单（加剧乱序问题）
     */
    @GetMapping("/simulateMultipleOrders")
    public String simulateMultipleOrders(@RequestParam(defaultValue = "3") int count) {
        try {
            log.info("🚀 开始并发模拟 {} 个订单流转", count);

            for (int i = 1; i <= count; i++) {
                String orderId = String.format("ORDER-%03d", incrementId++);

                // 快速发送，制造乱序场景
                sendStatusChange(orderId, OrderStatus.CREATED, 1);
                sendStatusChange(orderId, OrderStatus.PAID, 2);
                sendStatusChange(orderId, OrderStatus.SHIPPED, 3);
                sendStatusChange(orderId, OrderStatus.COMPLETED, 4);

                TimeUnit.MILLISECONDS.sleep(50);
            }

            return String.format("✅ 已发送 %d 个订单的状态流转消息", count);

        } catch (Exception e) {
            log.error("并发模拟订单失败", e);
            return "❌ 发送失败: " + e.getMessage();
        }
    }

    /**
     * 发送状态变更消息
     * Fixed: 使用 MessageGroup 实现 FIFO 顺序
     *
     * 关键点：
     * 1. setMessageGroup(orderId) - 每个订单独立的消息组
     * 2. 同一 MessageGroup 的消息保证 FIFO 顺序
     * 3. 不同 MessageGroup 之间可以并发处理
     */
    private void sendStatusChange(String orderId, OrderStatus status, int sequenceNo)
            throws Exception {

        OrderStatusEvent event = new OrderStatusEvent(orderId, status, sequenceNo);
        String messageBody = objectMapper.writeValueAsString(event);

        ClientServiceProvider provider = ClientServiceProvider.loadService();

        // ✅ Fixed: 使用 MessageGroup 实现 FIFO 顺序
        // 关键：每个订单使用独立的 MessageGroup（按 orderId 分区）
        // 效果：同一订单的消息严格 FIFO，不同订单可以并发处理
        Message message = provider.newMessageBuilder()
                .setTopic(TOPIC)
                .setTag("status-change")
                .setKeys(orderId)
                .setMessageGroup(orderId)  // ✅ 每个订单独立的 MessageGroup，保证订单内 FIFO，订单间并发
                .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
                .build();

        SendReceipt receipt = producer.send(message);

        log.info("📤 发送状态变更消息 - OrderId: {}, Status: {}, Seq: {}, MessageGroup: {}, MessageId: {}",
                orderId, status.getDescription(), sequenceNo, orderId, receipt.getMessageId());
    }

    /**
     * 测试说明
     */
    @GetMapping("/testGuide")
    public String testGuide() {
        return """
                📋 Level 5 测试指南

                1️⃣ 单订单测试：
                   curl "http://localhost:8070/challenge/level5/simulateOrderFlow?orderId=ORDER-001"

                   预期问题：消息可能乱序到达，导致状态转换失败

                2️⃣ 多订单并发测试：
                   curl "http://localhost:8070/challenge/level5/simulateMultipleOrders?count=5"

                   预期问题：更严重的乱序现象，多个订单状态混乱

                3️⃣ 查看订单状态：
                   curl "http://localhost:8070/challenge/level5/checkOrderStatus?orderId=ORDER-001"

                4️⃣ 重置测试环境：
                   curl "http://localhost:8070/challenge/level5/reset"

                🎯 观察要点：
                - 消费者日志中的 ⚠️ 乱序消息警告
                - 消费者日志中的 ❌ 状态转换非法错误
                - 订单状态统计中的错误数量

                💡 思考：
                - 为什么会出现乱序？
                - RocketMQ 如何保证消息顺序？
                - 如何实现顺序消费？
                """;
    }
}
