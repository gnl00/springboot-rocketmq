package one.demo.challenge.level2;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Level 2 最佳实践版本
 *
 * 主要优化点：
 * 1. ✅ 合理的超时时间和重试次数
 * 2. ✅ 使用异步发送，避免阻塞主线程
 * 3. ✅ 实现失败消息持久化兜底
 * 4. ✅ 批量发送并行化优化
 * 5. ✅ 完善的监控和统计
 */
@Slf4j
@RestController
@RequestMapping("/challenge/level2/best")
@RequiredArgsConstructor
public class Level2ProducerBest {

    private static final String ENDPOINTS = "localhost:8080";
    private Producer producer;

    private final FailedMessageStore failedMessageStore;

    @PostConstruct
    public void init() throws ClientException {
        log.info("初始化 RocketMQ Producer...");

        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(ENDPOINTS)
                // 优化1: 合理的超时时间 3-5秒
                .setRequestTimeout(Duration.ofSeconds(3))
                .build();

        this.producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                // 优化2: 合理的重试次数 2-3次
                .setMaxAttempts(3)
                .build();

        log.info("RocketMQ Producer 初始化完成");
    }

    /**
     * 优化版本1：异步发送 + 失败持久化
     */
    @GetMapping("/send")
    public String sendMessage(@RequestParam(defaultValue = "demo") String topic,
                              @RequestParam(defaultValue = "Hello RocketMQ") String message) {
        long startTime = System.currentTimeMillis();

        try {
            ClientServiceProvider provider = ClientServiceProvider.loadService();
            String messageId = UUID.randomUUID().toString();

            Message msg = provider.newMessageBuilder()
                    .setTopic(topic)
                    .setTag("best")
                    .setKeys(messageId)
                    .setBody(message.getBytes(StandardCharsets.UTF_8))
                    .build();

            // 优化3: 使用异步发送，立即返回，不阻塞主线程
            CompletableFuture<SendReceipt> future = producer.sendAsync(msg);

            // 优化4: 异步处理发送结果
            future.whenComplete((receipt, throwable) -> {
                if (throwable != null) {
                    // 发送失败：保存到失败消息表
                    log.error("消息发送失败，MessageId: {}, 错误: {}", messageId, throwable.getMessage());

                    // 优化5: 持久化失败消息
                    FailedMessage failedMessage = new FailedMessage(
                            messageId,
                            topic,
                            "best",
                            message,
                            throwable.getMessage(),
                            LocalDateTime.now(),
                            0,
                            "PENDING"
                    );
                    failedMessageStore.save(failedMessage);

                } else {
                    long cost = System.currentTimeMillis() - startTime;
                    log.info("消息发送成功，MessageId: {}, 耗时: {}ms", receipt.getMessageId(), cost);
                }
            });

            // 立即返回，不等待发送完成
            long cost = System.currentTimeMillis() - startTime;
            return String.format("消息已提交发送，MessageId: %s, 响应时间: %dms", messageId, cost);

        } catch (Exception e) {
            log.error("提交发送失败", e);
            return "提交失败: " + e.getMessage();
        }
    }

    /**
     * 优化版本2：批量异步并行发送
     */
    @GetMapping("/batchSend")
    public String batchSend(@RequestParam(defaultValue = "demo") String topic,
                           @RequestParam(defaultValue = "10") int count) {
        long startTime = System.currentTimeMillis();

        ClientServiceProvider provider = ClientServiceProvider.loadService();
        List<CompletableFuture<SendReceipt>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 优化6: 并行异步发送所有消息
        for (int i = 0; i < count; i++) {
            String messageId = "batch-" + UUID.randomUUID();
            String content = "消息-" + i;

            try {
                Message message = provider.newMessageBuilder()
                        .setTopic(topic)
                        .setTag("batch-best")
                        .setKeys(messageId)
                        .setBody(content.getBytes(StandardCharsets.UTF_8))
                        .build();

                // 异步发送，不等待
                CompletableFuture<SendReceipt> future = producer.sendAsync(message);

                // 处理每条消息的结果
                future.whenComplete((receipt, throwable) -> {
                    if (throwable != null) {
                        failCount.incrementAndGet();
                        log.error("批量发送失败，MessageId: {}", messageId);

                        // 保存失败消息
                        FailedMessage failedMessage = new FailedMessage(
                                messageId,
                                topic,
                                "batch-best",
                                content,
                                throwable.getMessage(),
                                LocalDateTime.now(),
                                0,
                                "PENDING"
                        );
                        failedMessageStore.save(failedMessage);

                    } else {
                        successCount.incrementAndGet();
                        log.info("批量发送成功，MessageId: {}", messageId);
                    }
                });

                futures.add(future);

            } catch (Exception e) {
                failCount.incrementAndGet();
                log.error("创建消息失败", e);
            }
        }

        // 立即返回，不等待所有消息发送完成
        long cost = System.currentTimeMillis() - startTime;

        // 注意：这里的 successCount 和 failCount 可能还是 0，因为异步发送还没完成
        return String.format(
                "批量发送已提交 - 总数: %d, 响应时间: %dms (实际发送结果请查看日志或调用统计接口)",
                count, cost
        );
    }

    /**
     * 同步批量发送（适用于需要立即知道结果的场景）
     */
    @GetMapping("/batchSendSync")
    public String batchSendSync(@RequestParam(defaultValue = "demo") String topic,
                               @RequestParam(defaultValue = "10") int count) {
        long startTime = System.currentTimeMillis();

        ClientServiceProvider provider = ClientServiceProvider.loadService();
        List<CompletableFuture<SendReceipt>> futures = new ArrayList<>();

        // 先提交所有异步发送
        for (int i = 0; i < count; i++) {
            String messageId = "batch-sync-" + i;
            try {
                Message message = provider.newMessageBuilder()
                        .setTopic(topic)
                        .setTag("batch-sync")
                        .setKeys(messageId)
                        .setBody(("消息-" + i).getBytes(StandardCharsets.UTF_8))
                        .build();

                futures.add(producer.sendAsync(message));
            } catch (Exception e) {
                log.error("创建消息失败", e);
            }
        }

        // 等待所有发送完成
        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < futures.size(); i++) {
            try {
                futures.get(i).join();  // 等待完成
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.error("批量发送第 {} 条消息失败", i + 1, e);

                // 保存失败消息
                FailedMessage failedMessage = new FailedMessage(
                        "batch-sync-" + i,
                        topic,
                        "batch-sync",
                        "消息-" + i,
                        e.getMessage(),
                        LocalDateTime.now(),
                        0,
                        "PENDING"
                );
                failedMessageStore.save(failedMessage);
            }
        }

        long cost = System.currentTimeMillis() - startTime;
        return String.format("批量发送完成 - 成功: %d, 失败: %d, 总耗时: %dms, 平均: %dms/msg",
                successCount, failCount, cost, cost / count);
    }

    /**
     * 查询失败消息列表
     */
    @GetMapping("/failedMessages")
    public List<FailedMessage> getFailedMessages() {
        return failedMessageStore.getAllFailedMessages();
    }

    /**
     * 获取失败消息统计
     */
    @GetMapping("/statistics")
    public String getStatistics() {
        return failedMessageStore.getStatistics();
    }

    /**
     * 手动重试失败的消息
     */
    @GetMapping("/retryFailed")
    public String retryFailedMessages() {
        List<FailedMessage> pendingMessages = failedMessageStore.getPendingMessages();

        if (pendingMessages.isEmpty()) {
            return "没有待重试的消息";
        }

        AtomicInteger retrySuccessCount = new AtomicInteger(0);
        AtomicInteger retryFailCount = new AtomicInteger(0);

        ClientServiceProvider provider = ClientServiceProvider.loadService();

        for (FailedMessage failedMsg : pendingMessages) {
            try {
                failedMessageStore.updateStatus(failedMsg.getMessageId(), "RETRYING");

                Message message = provider.newMessageBuilder()
                        .setTopic(failedMsg.getTopic())
                        .setTag(failedMsg.getTag())
                        .setKeys(failedMsg.getMessageId())
                        .setBody(failedMsg.getContent().getBytes(StandardCharsets.UTF_8))
                        .build();

                CompletableFuture<SendReceipt> future = producer.sendAsync(message);

                future.whenComplete((receipt, throwable) -> {
                    if (throwable != null) {
                        retryFailCount.incrementAndGet();
                        failedMessageStore.incrementRetryCount(failedMsg.getMessageId());

                        // 重试3次后标记为最终失败
                        if (failedMsg.getRetryCount() >= 2) {
                            failedMessageStore.updateStatus(failedMsg.getMessageId(), "FAILED");
                            log.error("消息最终重试失败，MessageId: {}", failedMsg.getMessageId());
                        } else {
                            failedMessageStore.updateStatus(failedMsg.getMessageId(), "PENDING");
                        }
                    } else {
                        retrySuccessCount.incrementAndGet();
                        failedMessageStore.remove(failedMsg.getMessageId());
                        log.info("消息重试成功，MessageId: {}", failedMsg.getMessageId());
                    }
                });

            } catch (Exception e) {
                retryFailCount.incrementAndGet();
                log.error("重试消息异常，MessageId: {}", failedMsg.getMessageId(), e);
            }
        }

        return String.format("已提交重试 - 总数: %d, 成功: %d, 失败: %d",
                pendingMessages.size(), retrySuccessCount.get(), retryFailCount.get());
    }

    @GetMapping("/health")
    public String health() {
        Runtime runtime = Runtime.getRuntime();
        return String.format(
            "系统状态 - 内存使用: %d MB, 活跃线程: %d, %s",
            (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024,
            Thread.activeCount(),
            failedMessageStore.getStatistics()
        );
    }
}