package one.demo.challenge.level4;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Level 4 最佳实践：消息积压优化
 *
 * 核心优化策略：
 * 1. ✅ 增加消费并发度（多线程消费）
 * 2. ✅ 异步化耗时操作（不阻塞消费线程）
 * 3. ✅ 批量处理优化
 * 4. ✅ 业务逻辑优化（缓存、异步通知等）
 *
 * 性能对比：
 * - Buggy 版本：2 msg/s (单线程 × 500ms)
 * - Best 版本：200+ msg/s (异步 + 并发)
 */
@Slf4j
// @Component
@RocketMQMessageListener(
        topic = "order-notification",
        tag = "*",
        consumerGroup = "notification-consumer-best",
        endpoints = "localhost:8080"
        // 注意：RocketMQ Spring Boot Starter 的消费并发度配置
        // 需要在 application.yml 中配置或通过 consumeThreadNumber 参数
)
public class Level4ConsumerBest implements RocketMQListener {

    private static final AtomicLong consumedCount = new AtomicLong(0);
    private static final AtomicLong startTime = new AtomicLong(System.currentTimeMillis());

    // 优化1：使用异步线程池处理耗时操作，不阻塞消费线程
    private final ExecutorService asyncProcessExecutor = new ThreadPoolExecutor(
            20,  // 核心线程数
            50,  // 最大线程数
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()  // 队列满时由调用线程执行
    );

    @Override
    public ConsumeResult consume(MessageView messageView) {
        long currentCount = consumedCount.incrementAndGet();

        try {
            String messageBody = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();

            // 优化2：异步处理，立即返回 ACK
            // 注意：这种方式风险是如果异步处理失败，消息已经 ACK 了
            // 生产环境需要配合失败重试机制（如写入重试队列）
            CompletableFuture.runAsync(() -> {
                try {
                    processOptimizedOperation(messageBody);
                } catch (Exception e) {
                    log.error("异步处理失败 - Message: {}", messageBody, e);
                    // TODO: 写入失败队列，后续重试
                }
            }, asyncProcessExecutor);

            // 计算消费速率
            if (currentCount % 100 == 0) {
                long elapsed = System.currentTimeMillis() - startTime.get();
                double rate = currentCount * 1000.0 / elapsed;
                log.info(String.format("🚀 消费统计（优化版）- 已消费: %d 条, 耗时: %d ms, 速率: %.2f msg/s",
                        currentCount, elapsed, rate));
            }

            return ConsumeResult.SUCCESS;

        } catch (Exception e) {
            log.error("消费消息失败 - MessageId: {}", messageView.getMessageId(), e);
            return ConsumeResult.FAILURE;
        }
    }

    /**
     * 优化后的处理逻辑
     * 核心优化点：
     * 1. 使用缓存减少数据库查询
     * 2. 批量处理减少网络开销
     * 3. 异步通知不阻塞主流程
     */
    private void processOptimizedOperation(String message) throws InterruptedException {
        // 优化3：使用缓存，减少数据库查询时间（100ms → 10ms）
        queryFromCache();

        // 优化4：批量调用第三方 API（200ms → 50ms）
        // 实际场景：收集多条消息，批量调用
        callThirdPartyApiBatch();

        // 优化5：异步发送通知，不等待结果（200ms → 0ms）
        sendNotificationAsync();

        // 优化后总耗时：10ms + 50ms + 0ms = 60ms
        // 相比原来的 500ms，提升了 8 倍
    }

    /**
     * 从缓存查询（优化数据库查询）
     */
    private void queryFromCache() throws InterruptedException {
        // 模拟缓存查询（快速）
        TimeUnit.MILLISECONDS.sleep(10);
    }

    /**
     * 批量调用第三方 API（优化网络请求）
     */
    private void callThirdPartyApiBatch() throws InterruptedException {
        // 模拟批量 API 调用
        TimeUnit.MILLISECONDS.sleep(50);
    }

    /**
     * 异步发送通知（不阻塞主流程）
     */
    private void sendNotificationAsync() {
        // 异步发送，立即返回
        CompletableFuture.runAsync(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(200);
                log.debug("通知发送成功");
            } catch (Exception e) {
                log.error("通知发送失败", e);
            }
        });
    }
}
