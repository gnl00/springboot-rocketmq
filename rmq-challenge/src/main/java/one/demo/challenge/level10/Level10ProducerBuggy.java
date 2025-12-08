package one.demo.challenge.level10;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Level 10 挑战：消息批量处理与流量控制（Buggy 版本）
 *
 * 问题场景：
 * 电商系统需要处理大量订单消息，为了提高性能，需要实现批量处理。
 * 但是当前实现存在多个问题，导致性能低下、资源浪费、消息积压。
 *
 * 问题现象：
 * 1. 消费者逐条处理消息，没有批量处理，导致数据库压力大
 * 2. 没有流量控制，高峰期消费者被打爆，OOM
 * 3. 批量发送消息时，一条失败导致整批失败
 * 4. 消费者线程池配置不合理，CPU 利用率低
 * 5. 没有背压机制，生产者无限制发送消息
 *
 * Bug 分析：
 * 1. 消费者每次只处理一条消息，频繁调用数据库
 * 2. 消费者线程数配置过少，无法充分利用 CPU
 * 3. 批量发送时没有做异常隔离，一条失败全部失败
 * 4. 没有限流机制，高峰期消息堆积导致 OOM
 * 5. 消费者没有实现本地批量缓存，无法批量提交
 *
 * 任务：
 * 1. 运行测试，观察问题现象
 * 2. 分析为什么会出现这些问题
 * 3. 设计并实现解决方案
 *
 * 提示：
 * - 考虑使用本地队列缓存消息，达到阈值后批量处理
 * - 考虑使用 Semaphore 或 RateLimiter 进行流量控制
 * - 批量发送时要做好异常隔离
 * - 合理配置消费者线程池大小
 */
@Slf4j
@RestController
@RequestMapping("/challenge/level10/buggy")
public class Level10ProducerBuggy {

    @Autowired
    private Level10OrderService orderService;

    private Producer producer;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules(); // 自动注册 JSR310 模块支持 Java 8 日期时间

    @PostConstruct
    public void init() throws ClientException {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(Level10Constants.ENDPOINTS)
                .setRequestTimeout(Duration.ofSeconds(3))
                .build();

        this.producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics(Level10Constants.BATCH_ORDER_TOPIC)
                .build();

        log.info("✅ Level 10 Producer (Buggy) 初始化完成");
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
     * 发送单个订单消息
     */
    @GetMapping("/sendOrder")
    public String sendOrder(
            @RequestParam(defaultValue = "USER-001") String userId,
            @RequestParam(defaultValue = "NORMAL") String orderType,
            @RequestParam(defaultValue = "100.00") BigDecimal amount) {

        String orderId = "ORDER-" + UUID.randomUUID().toString().substring(0, 8);

        try {
            Level10Order order = new Level10Order(
                    orderId,
                    userId,
                    Level10OrderType.valueOf(orderType),
                    amount
            );

            orderService.saveOrder(order);

            String messageBody = objectMapper.writeValueAsString(order);
            Message message = ClientServiceProvider.loadService()
                    .newMessageBuilder()
                    .setTopic(Level10Constants.BATCH_ORDER_TOPIC)
                    .setTag(orderType)
                    .setKeys(orderId)
                    .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
                    .build();

            SendReceipt receipt = producer.send(message);
            log.info("📤 [Buggy] 订单消息已发送 - OrderId: {}, MessageId: {}", orderId, receipt.getMessageId());

            return String.format("""
                    ✅ 订单消息已发送
                    - OrderId: %s
                    - UserId: %s
                    - OrderType: %s
                    - Amount: %.2f
                    - MessageId: %s

                    ⚠️ Bug 提示：
                    消费者会逐条处理消息，没有批量处理优化
                    """, orderId, userId, orderType, amount, receipt.getMessageId());

        } catch (Exception e) {
            log.error("❌ [Buggy] 发送订单消息失败", e);
            return "❌ 发送失败: " + e.getMessage();
        }
    }

    /**
     * 批量发送订单消息（Buggy 版本）
     * Bug: 一条失败导致整批失败，没有异常隔离
     */
    @GetMapping("/batchSend")
    public String batchSend(@RequestParam(defaultValue = "50") int count) {
        if (count > 1000) {
            return "❌ 批量发送数量不能超过 1000";
        }

        List<String> successOrderIds = new ArrayList<>();
        List<String> failedOrderIds = new ArrayList<>();

        long startTime = System.currentTimeMillis();

        try {
            // Bug 1: 没有做异常隔离，一条失败可能导致整批失败
            for (int i = 0; i < count; i++) {
                String orderId = "BATCH-ORDER-" + UUID.randomUUID().toString().substring(0, 8);

                Level10Order order = new Level10Order(
                        orderId,
                        "USER-" + (i % 100),
                        Level10OrderType.BULK,
                        BigDecimal.valueOf(100 + i)
                );

                orderService.saveOrder(order);

                // Bug 2: 模拟偶发性失败，但没有重试机制
                if (i % 37 == 0) {
                    log.error("❌ [Buggy] 模拟发送失败 - OrderId: {}", orderId);
                    failedOrderIds.add(orderId);
                    // Bug 3: 这里应该继续处理，但可能因为异常处理不当导致中断
                    throw new RuntimeException("模拟网络异常");
                }

                String messageBody = objectMapper.writeValueAsString(order);
                Message message = ClientServiceProvider.loadService()
                        .newMessageBuilder()
                        .setTopic(Level10Constants.BATCH_ORDER_TOPIC)
                        .setTag("BULK")
                        .setKeys(orderId)
                        .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
                        .build();

                producer.send(message);
                successOrderIds.add(orderId);
            }

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("❌ [Buggy] 批量发送失败", e);

            return String.format("""
                    ❌ 批量发送失败
                    - 请求数量: %d
                    - 成功数量: %d
                    - 失败数量: %d
                    - 耗时: %d ms

                    🔍 Bug 现象：
                    一条消息发送失败，导致整批发送中断！
                    剩余 %d 条消息未发送。

                    💡 建议：
                    应该做好异常隔离，单条失败不影响其他消息
                    """, count, successOrderIds.size(), failedOrderIds.size(),
                    duration, count - successOrderIds.size() - failedOrderIds.size());
        }

        long duration = System.currentTimeMillis() - startTime;

        return String.format("""
                ✅ 批量发送完成
                - 请求数量: %d
                - 成功数量: %d
                - 失败数量: %d
                - 耗时: %d ms
                - 平均耗时: %.2f ms/条

                ⚠️ Bug 提示：
                1. 没有异常隔离，一条失败可能导致整批失败
                2. 消费者会逐条处理，性能低下
                3. 没有流量控制，高峰期可能导致消费者 OOM
                """, count, successOrderIds.size(), failedOrderIds.size(),
                duration, (double) duration / count);
    }

    /**
     * 模拟高并发场景（压力测试）
     * Bug: 没有流量控制，可能导致消费者 OOM
     */
    @GetMapping("/stressTest")
    public String stressTest(@RequestParam(defaultValue = "500") int count) {
        if (count > 5000) {
            return "❌ 压力测试数量不能超过 5000";
        }

        long startTime = System.currentTimeMillis();
        int successCount = 0;

        // Bug: 没有任何流量控制，直接发送大量消息
        for (int i = 0; i < count; i++) {
            try {
                String orderId = "STRESS-ORDER-" + UUID.randomUUID().toString().substring(0, 8);

                Level10Order order = new Level10Order(
                        orderId,
                        "USER-" + (i % 100),
                        Level10OrderType.URGENT,
                        BigDecimal.valueOf(100 + i)
                );

                orderService.saveOrder(order);

                String messageBody = objectMapper.writeValueAsString(order);
                Message message = ClientServiceProvider.loadService()
                        .newMessageBuilder()
                        .setTopic(Level10Constants.BATCH_ORDER_TOPIC)
                        .setTag("URGENT")
                        .setKeys(orderId)
                        .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
                        .build();

                producer.send(message);
                successCount++;

            } catch (Exception e) {
                log.error("❌ [Buggy] 压力测试发送失败", e);
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        return String.format("""
                ✅ 压力测试完成
                - 请求数量: %d
                - 成功数量: %d
                - 失败数量: %d
                - 总耗时: %d ms
                - 平均耗时: %.2f ms/条
                - 发送速率: %.2f 条/秒

                ⚠️ Bug 提示：
                1. 没有流量控制，消费者可能被打爆
                2. 消费者逐条处理，无法应对高并发
                3. 可能导致消息积压、内存溢出

                💡 建议：
                - 查看消费者日志，观察处理速度
                - 使用 curl "http://localhost:8070/challenge/level10/buggy/stats" 查看统计
                """, count, successCount, count - successCount, duration,
                (double) duration / count, (double) successCount * 1000 / duration);
    }

    /**
     * 查看统计信息
     */
    @GetMapping("/stats")
    public String stats() {
        return orderService.getStats();
    }

    /**
     * 重置统计
     */
    @GetMapping("/reset")
    public String reset() {
        orderService.reset();
        return "✅ 统计已重置";
    }

    /**
     * 帮助信息
     */
    @GetMapping("/help")
    public String help() {
        return """
                🆘 Level 10 Buggy 版本说明

                问题场景：消息批量处理与流量控制

                测试接口：
                1. 发送单个订单：
                   curl "http://localhost:8070/challenge/level10/buggy/sendOrder?userId=USER-001&orderType=NORMAL&amount=100"

                2. 批量发送（观察异常隔离问题）：
                   curl "http://localhost:8070/challenge/level10/buggy/batchSend?count=50"

                3. 压力测试（观察流量控制问题）：
                   curl "http://localhost:8070/challenge/level10/buggy/stressTest?count=500"

                4. 查看统计：
                   curl "http://localhost:8070/challenge/level10/buggy/stats"

                5. 重置统计：
                   curl "http://localhost:8070/challenge/level10/buggy/reset"

                Bug 列表：
                1. 消费者逐条处理消息，没有批量处理优化
                2. 批量发送时一条失败导致整批失败
                3. 没有流量控制，高峰期消费者被打爆
                4. 消费者线程池配置不合理
                5. 没有背压机制

                任务：
                1. 运行测试，观察问题现象
                2. 分析为什么会出现这些问题
                3. 设计并实现 Fixed 版本
                """;
    }
}
