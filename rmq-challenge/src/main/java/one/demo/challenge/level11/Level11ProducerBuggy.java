package one.demo.challenge.level11;

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
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * Level 11 挑战：消息轨迹追踪与可观测性（Buggy 版本）
 *
 * 问题场景：
 * 生产环境中，消息系统出现了各种问题：
 * 1. 某些订单消息处理很慢，但不知道慢在哪里（发送慢？消费慢？业务处理慢？）
 * 2. 消息偶尔丢失，但无法追踪消息的完整生命周期
 * 3. 消费失败后，不知道失败原因和重试次数
 * 4. 无法统计消息的端到端延迟
 * 5. 出现问题时，无法快速定位是哪个环节出了问题
 *
 * 问题现象：
 * 1. 没有消息轨迹记录，无法追踪消息流转
 * 2. 没有性能指标采集，无法分析性能瓶颈
 * 3. 没有错误日志聚合，排查问题困难
 * 4. 没有监控告警，问题发现滞后
 * 5. 缺少可视化界面，运维困难
 *
 * Bug 分析：
 * 1. 发送消息时没有记录轨迹信息
 * 2. 消费消息时没有记录性能指标
 * 3. 没有统一的 TraceId 贯穿整个链路
 * 4. 没有采集关键时间点（发送时间、接收时间、处理时间）
 * 5. 没有错误信息记录和分析
 *
 * 任务：
 * 1. 运行测试，观察缺少轨迹追踪的问题
 * 2. 分析为什么无法定位性能瓶颈
 * 3. 设计并实现完整的轨迹追踪方案
 *
 * 提示：
 * - 使用 TraceId 贯穿整个消息链路
 * - 记录关键时间点：发送、Broker接收、消费开始、消费结束
 * - 计算各阶段延迟：Broker延迟、消费者延迟、处理耗时、总延迟
 * - 记录错误信息和重试次数
 * - 提供查询接口：按 TraceId、OrderId、慢消息、失败消息查询
 */
@Slf4j
@RestController
@RequestMapping("/challenge/level11/buggy")
public class Level11ProducerBuggy {

    @Autowired
    private Level11TraceService traceService;

    private Producer producer;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules();

    @PostConstruct
    public void init() throws ClientException {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(Level11Constants.ENDPOINTS)
                .setRequestTimeout(Duration.ofSeconds(3))
                .build();

        this.producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics(Level11Constants.ORDER_TOPIC)
                .build();

        log.info("✅ Level 11 Producer (Buggy) 初始化完成");
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
     * 发送订单消息
     * Bug: 没有记录任何轨迹信息
     */
    @GetMapping("/sendOrder")
    public String sendOrder(
            @RequestParam(defaultValue = "ORDER-001") String orderId,
            @RequestParam(defaultValue = "USER-001") String userId,
            @RequestParam(defaultValue = "PRODUCT-001") String productId,
            @RequestParam(defaultValue = "1") Integer quantity,
            @RequestParam(defaultValue = "100.00") BigDecimal amount,
            @RequestParam(defaultValue = "NORMAL") Level11ProcessingMode mode) {

        try {
            // Bug 1: 没有生成 TraceId
            String traceId = UUID.randomUUID().toString();

            Level11OrderMessage message = new Level11OrderMessage(
                    traceId, orderId, userId, productId, quantity, amount, mode
            );

            String messageBody = objectMapper.writeValueAsString(message);

            // Bug 2: 发送消息前没有记录轨迹
            Message mqMessage = ClientServiceProvider.loadService()
                    .newMessageBuilder()
                    .setTopic(Level11Constants.ORDER_TOPIC)
                    .setTag(mode.name())
                    .setKeys(orderId)
                    .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
                    .build();

            SendReceipt receipt = producer.send(mqMessage);

            // Bug 3: 发送成功后也没有记录轨迹
            log.info("📤 [Buggy] 订单消息已发送 - OrderId: {}, TraceId: {}, MessageId: {}",
                    orderId, traceId, receipt.getMessageId());

            return String.format("""
                    ✅ 订单消息已发送
                    - OrderId: %s
                    - TraceId: %s
                    - MessageId: %s
                    - Mode: %s (%s)

                    ⚠️ Bug 提示：
                    没有记录消息轨迹，无法追踪消息流转！

                    💡 测试建议：
                    - 发送不同模式的消息，观察处理情况
                    - 尝试查询轨迹信息（会发现查不到）
                    - curl "http://localhost:8086/challenge/level11/buggy/stats"
                    """, orderId, traceId, receipt.getMessageId().toString().substring(0, 16),
                    mode, mode.getDescription());

        } catch (Exception e) {
            log.error("❌ [Buggy] 发送订单消息失败", e);
            return "❌ 发送失败: " + e.getMessage();
        }
    }

    /**
     * 批量发送不同模式的消息
     * Bug: 无法追踪哪些消息慢、哪些消息失败
     */
    @GetMapping("/batchSend")
    public String batchSend(@RequestParam(defaultValue = "10") int count) {
        if (count > 50) {
            return "❌ 批量发送数量不能超过 50";
        }

        int successCount = 0;
        Level11ProcessingMode[] modes = Level11ProcessingMode.values();

        try {
            for (int i = 0; i < count; i++) {
                String orderId = "ORDER-" + String.format("%03d", i + 1);
                String userId = "USER-" + String.format("%03d", (i % 10) + 1);
                String traceId = UUID.randomUUID().toString();

                // 轮流使用不同的处理模式
                Level11ProcessingMode mode = modes[i % modes.length];
                MDC.put("traceId", traceId);
                Level11OrderMessage message = new Level11OrderMessage(
                        traceId, orderId, userId, "PRODUCT-001", 1,
                        BigDecimal.valueOf(100 + i), mode
                );

                String messageBody = objectMapper.writeValueAsString(message);

                // Bug: 批量发送时也没有记录轨迹
                Message mqMessage = ClientServiceProvider.loadService()
                        .newMessageBuilder()
                        .setTopic(Level11Constants.ORDER_TOPIC)
                        .setTag(mode.name())
                        .setKeys(orderId)
                        .addProperty("traceId", traceId)
                        .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
                        .build();

                producer.send(mqMessage);
                successCount++;

                Thread.sleep(10);
            }

            return String.format("""
                    ✅ 批量发送完成
                    - 请求数量: %d
                    - 成功数量: %d
                    - 包含模式: FAST, NORMAL, SLOW, VERY_SLOW, RANDOM_FAIL

                    ⚠️ Bug 提示：
                    1. 没有轨迹记录，无法知道哪些消息处理慢
                    2. 没有性能指标，无法分析瓶颈
                    3. 没有错误追踪，无法定位失败原因

                    💡 测试建议：
                    - 等待消费完成后查看统计
                    - 尝试查询慢消息（会发现查不到）
                    - 尝试查询失败消息（会发现没有详细信息）
                    - curl "http://localhost:8086/challenge/level11/buggy/stats"
                    """, count, successCount);

        } catch (Exception e) {
            log.error("❌ [Buggy] 批量发送失败", e);
            return "❌ 批量发送失败: " + e.getMessage();
        }
    }

    /**
     * 帮助信息
     */
    @GetMapping("/help")
    public String help() {
        return """
                🆘 Level 11 Buggy 版本说明

                问题场景：消息轨迹追踪与可观测性

                测试接口：
                1. 发送单个订单（不同模式）：
                   curl "http://localhost:8086/challenge/level11/buggy/sendOrder?orderId=ORDER-001&mode=FAST"
                   curl "http://localhost:8086/challenge/level11/buggy/sendOrder?orderId=ORDER-002&mode=SLOW"
                   curl "http://localhost:8086/challenge/level11/buggy/sendOrder?orderId=ORDER-003&mode=RANDOM_FAIL"

                2. 批量发送（包含所有模式）：
                   curl "http://localhost:8086/challenge/level11/buggy/batchSend?count=10"

                3. 查看统计信息：
                   curl "http://localhost:8086/challenge/level11/buggy/stats"

                4. 查询消息轨迹（会发现查不到）：
                   curl "http://localhost:8086/challenge/level11/buggy/queryTrace?traceId=xxx"

                5. 查询慢消息（会发现查不到）：
                   curl "http://localhost:8086/challenge/level11/buggy/slowMessages?threshold=1000"

                6. 重置统计：
                   curl "http://localhost:8086/challenge/level11/buggy/reset"

                处理模式说明：
                - FAST: 快速处理（50ms）
                - NORMAL: 正常处理（200ms）
                - SLOW: 慢处理（1000ms）
                - VERY_SLOW: 超慢处理（3000ms）
                - RANDOM_FAIL: 随机失败（50%概率）

                Bug 列表：
                1. 没有生成和传递 TraceId
                2. 没有记录消息发送时间
                3. 没有记录 Broker 接收时间
                4. 没有记录消费开始/结束时间
                5. 没有计算各阶段延迟
                6. 没有记录错误信息
                7. 没有记录重试次数
                8. 无法查询慢消息
                9. 无法查询失败消息
                10. 缺少可视化和监控

                任务：
                1. 运行测试，观察缺少轨迹追踪的问题
                2. 分析为什么无法定位性能瓶颈
                3. 设计并实现 Fixed 版本
                """;
    }
}
