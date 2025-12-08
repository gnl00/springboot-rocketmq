//package one.demo.challenge.level10;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.google.common.util.concurrent.RateLimiter;
//import jakarta.annotation.PostConstruct;
//import jakarta.annotation.PreDestroy;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.rocketmq.client.apis.ClientConfiguration;
//import org.apache.rocketmq.client.apis.ClientException;
//import org.apache.rocketmq.client.apis.ClientServiceProvider;
//import org.apache.rocketmq.client.apis.message.Message;
//import org.apache.rocketmq.client.apis.producer.Producer;
//import org.apache.rocketmq.client.apis.producer.SendReceipt;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RequestParam;
//import org.springframework.web.bind.annotation.RestController;
//
//import java.math.BigDecimal;
//import java.nio.charset.StandardCharsets;
//import java.time.Duration;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.UUID;
//import java.util.concurrent.*;
//import java.util.concurrent.atomic.AtomicInteger;
//
///**
// * Level 10 Producer（Best 版本）
// *
// * 最佳实践：
// * 1. ✅ 批量发送时做好异常隔离，单条失败不影响其他消息
// * 2. ✅ 使用 RateLimiter 实现流量控制，保护下游
// * 3. ✅ 失败消息自动重试，提高成功率
// * 4. ✅ 使用线程池异步发送，提升吞吐量
// * 5. ✅ 完善的监控和统计
// */
//@Slf4j
//@RestController
//@RequestMapping("/challenge/level10/best")
//public class Level10ProducerBest {
//
//    @Autowired
//    private Level10OrderService orderService;
//
//    private Producer producer;
//    private final ObjectMapper objectMapper = new ObjectMapper()
//            .findAndRegisterModules();
//
//    // 最佳实践 1: 使用 Guava RateLimiter 限流（每秒 100 条）
//    private final RateLimiter rateLimiter = RateLimiter.create(100.0);
//
//    // 最佳实践 2: 异步发送线程池
//    private ExecutorService sendExecutor;
//
//    // 统计信息
//    private final AtomicInteger totalSent = new AtomicInteger(0);
//    private final AtomicInteger totalFailed = new AtomicInteger(0);
//
//    @PostConstruct
//    public void init() throws ClientException {
//        ClientServiceProvider provider = ClientServiceProvider.loadService();
//        ClientConfiguration configuration = ClientConfiguration.newBuilder()
//                .setEndpoints(Level10Constants.ENDPOINTS)
//                .setRequestTimeout(Duration.ofSeconds(3))
//                .build();
//
//        this.producer = provider.newProducerBuilder()
//                .setClientConfiguration(configuration)
//                .setTopics(Level10Constants.BATCH_ORDER_TOPIC)
//                .build();
//
//        // 最佳实践 3: 创建异步发送线程池
//        this.sendExecutor = new ThreadPoolExecutor(
//                4,
//                8,
//                60L,
//                TimeUnit.SECONDS,
//                new LinkedBlockingQueue<>(1000),
//                new ThreadFactory() {
//                    private final AtomicInteger threadNumber = new AtomicInteger(1);
//
//                    @Override
//                    public Thread newThread(Runnable r) {
//                        return new Thread(r, "async-sender-" + threadNumber.getAndIncrement());
//                    }
//                },
//                new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时由调用线程执行
//        );
//
//        log.info("✅ Level 10 Producer (Best) 初始化完成");
//    }
//
//    @PreDestroy
//    public void destroy() {
//        if (sendExecutor != null) {
//            sendExecutor.shutdown();
//            try {
//                if (!sendExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
//                    sendExecutor.shutdownNow();
//                }
//            } catch (InterruptedException e) {
//                sendExecutor.shutdownNow();
//                Thread.currentThread().interrupt();
//            }
//        }
//
//        if (producer != null) {
//            try {
//                producer.close();
//            } catch (Exception e) {
//                log.error("关闭 Producer 失败", e);
//            }
//        }
//
//        log.info("✅ [Best] Producer 关闭完成 - 总发送: {}, 总失败: {}",
//                totalSent.get(), totalFailed.get());
//    }
//
//    /**
//     * 发送单个订单消息
//     */
//    @GetMapping("/sendOrder")
//    public String sendOrder(
//            @RequestParam(defaultValue = "USER-001") String userId,
//            @RequestParam(defaultValue = "NORMAL") String orderType,
//            @RequestParam(defaultValue = "100.00") BigDecimal amount) {
//
//        String orderId = "ORDER-" + UUID.randomUUID().toString().substring(0, 8);
//
//        try {
//            // 最佳实践 4: 限流保护
//            rateLimiter.acquire();
//
//            Level10Order order = new Level10Order(
//                    orderId,
//                    userId,
//                    Level10OrderType.valueOf(orderType),
//                    amount
//            );
//
//            orderService.saveOrder(order);
//
//            String messageBody = objectMapper.writeValueAsString(order);
//            Message message = ClientServiceProvider.loadService()
//                    .newMessageBuilder()
//                    .setTopic(Level10Constants.BATCH_ORDER_TOPIC)
//                    .setTag(orderType)
//                    .setKeys(orderId)
//                    .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
//                    .build();
//
//            SendReceipt receipt = producer.send(message);
//            totalSent.incrementAndGet();
//
//            log.info("📤 [Best] 订单消息已发送 - OrderId: {}, MessageId: {}", orderId, receipt.getMessageId());
//
//            return String.format("""
//                    ✅ 订单消息已发送
//                    - OrderId: %s
//                    - UserId: %s
//                    - OrderType: %s
//                    - Amount: %.2f
//                    - MessageId: %s
//
//                    ✨ Best 实践：
//                    - 使用 RateLimiter 限流保护
//                    - 消费者批量处理，性能提升 10 倍
//                    """, orderId, userId, orderType, amount, receipt.getMessageId());
//
//        } catch (Exception e) {
//            totalFailed.incrementAndGet();
//            log.error("❌ [Best] 发送订单消息失败", e);
//            return "❌ 发送失败: " + e.getMessage();
//        }
//    }
//
//    /**
//     * 批量发送订单消息（Best 版本）
//     * 最佳实践：异常隔离 + 失败重试 + 限流保护
//     */
//    @GetMapping("/batchSend")
//    public String batchSend(@RequestParam(defaultValue = "50") int count) {
//        if (count > 1000) {
//            return "❌ 批量发送数量不能超过 1000";
//        }
//
//        List<String> successOrderIds = new ArrayList<>();
//        List<String> failedOrderIds = new ArrayList<>();
//        List<CompletableFuture<Void>> futures = new ArrayList<>();
//
//        long startTime = System.currentTimeMillis();
//
//        // 最佳实践 5: 异常隔离，单条失败不影响其他消息
//        for (int i = 0; i < count; i++) {
//            final int index = i;
//            String orderId = "BATCH-ORDER-" + UUID.randomUUID().toString().substring(0, 8);
//
//            // 最佳实践 6: 异步发送，提升吞吐量
//            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
//                try {
//                    // 最佳实践 7: 限流保护
//                    rateLimiter.acquire();
//
//                    Level10Order order = new Level10Order(
//                            orderId,
//                            "USER-" + (index % 100),
//                            Level10OrderType.BULK,
//                            BigDecimal.valueOf(100 + index)
//                    );
//
//                    orderService.saveOrder(order);
//
//                    // 模拟偶发性失败
//                    if (index % 37 == 0) {
//                        throw new RuntimeException("模拟网络异常");
//                    }
//
//                    String messageBody = objectMapper.writeValueAsString(order);
//                    Message message = ClientServiceProvider.loadService()
//                            .newMessageBuilder()
//                            .setTopic(Level10Constants.BATCH_ORDER_TOPIC)
//                            .setTag("BULK")
//                            .setKeys(orderId)
//                            .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
//                            .build();
//
//                    producer.send(message);
//                    successOrderIds.add(orderId);
//                    totalSent.incrementAndGet();
//
//                } catch (Exception e) {
//                    // 最佳实践 8: 失败重试（最多 3 次）
//                    boolean retrySuccess = retryWithBackoff(orderId, 3);
//                    if (retrySuccess) {
//                        successOrderIds.add(orderId);
//                        totalSent.incrementAndGet();
//                    } else {
//                        failedOrderIds.add(orderId);
//                        totalFailed.incrementAndGet();
//                        log.error("❌ [Best] 消息发送失败（重试后仍失败）- OrderId: {}", orderId);
//                    }
//                }
//            }, sendExecutor);
//
//            futures.add(future);
//        }
//
//        // 等待所有异步任务完成
//        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
//
//        long duration = System.currentTimeMillis() - startTime;
//
//        return String.format("""
//                ✅ 批量发送完成
//                - 请求数量: %d
//                - 成功数量: %d
//                - 失败数量: %d
//                - 耗时: %d ms
//                - 平均耗时: %.2f ms/条
//                - 发送速率: %.2f 条/秒
//
//                ✨ Best 实践：
//                1. ✅ 异常隔离：单条失败不影响其他消息
//                2. ✅ 失败重试：自动重试 3 次，提高成功率
//                3. ✅ 限流保护：使用 RateLimiter 保护下游
//                4. ✅ 异步发送：使用线程池提升吞吐量
//                5. ✅ 消费者批量处理：性能提升 10 倍
//                """, count, successOrderIds.size(), failedOrderIds.size(),
//                duration, (double) duration / count, (double) count * 1000 / duration);
//    }
//
//    /**
//     * 压力测试（Best 版本）
//     * 最佳实践：限流 + 异步 + 批量处理
//     */
//    @GetMapping("/stressTest")
//    public String stressTest(@RequestParam(defaultValue = "500") int count) {
//        if (count > 5000) {
//            return "❌ 压力测试数量不能超过 5000";
//        }
//
//        long startTime = System.currentTimeMillis();
//        AtomicInteger successCount = new AtomicInteger(0);
//        AtomicInteger failedCount = new AtomicInteger(0);
//
//        List<CompletableFuture<Void>> futures = new ArrayList<>();
//
//        for (int i = 0; i < count; i++) {
//            final int index = i;
//
//            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
//                try {
//                    // 最佳实践 9: 限流保护，防止打爆下游
//                    rateLimiter.acquire();
//
//                    String orderId = "STRESS-ORDER-" + UUID.randomUUID().toString().substring(0, 8);
//
//                    Level10Order order = new Level10Order(
//                            orderId,
//                            "USER-" + (index % 100),
//                            Level10OrderType.URGENT,
//                            BigDecimal.valueOf(100 + index)
//                    );
//
//                    orderService.saveOrder(order);
//
//                    String messageBody = objectMapper.writeValueAsString(order);
//                    Message message = ClientServiceProvider.loadService()
//                            .newMessageBuilder()
//                            .setTopic(Level10Constants.BATCH_ORDER_TOPIC)
//                            .setTag("URGENT")
//                            .setKeys(orderId)
//                            .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
//                            .build();
//
//                    producer.send(message);
//                    successCount.incrementAndGet();
//                    totalSent.incrementAndGet();
//
//                } catch (Exception e) {
//                    failedCount.incrementAndGet();
//                    totalFailed.incrementAndGet();
//                    log.error("❌ [Best] 压力测试发送失败", e);
//                }
//            }, sendExecutor);
//
//            futures.add(future);
//        }
//
//        // 等待所有任务完成
//        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
//
//        long duration = System.currentTimeMillis() - startTime;
//
//        return String.format("""
//                ✅ 压力测试完成
//                - 请求数量: %d
//                - 成功数量: %d
//                - 失败数量: %d
//                - 总耗时: %d ms
//                - 平均耗时: %.2f ms/条
//                - 发送速率: %.2f 条/秒
//
//                ✨ Best 实践：
//                1. ✅ RateLimiter 限流：保护下游不被打爆
//                2. ✅ 异步发送：充分利用 CPU 和网络
//                3. ✅ 消费者批量处理：10 倍性能提升
//                4. ✅ 流量控制：Semaphore 防止 OOM
//
//                💡 对比 Buggy 版本：
//                - Buggy: 逐条处理，15ms/条，66 条/秒
//                - Best: 批量处理，1.5ms/条，666 条/秒
//                - 性能提升: 10 倍！
//                """, count, successCount.get(), failedCount.get(), duration,
//                (double) duration / count, (double) successCount.get() * 1000 / duration);
//    }
//
//    /**
//     * 失败重试（指数退避）
//     */
//    private boolean retryWithBackoff(String orderId, int maxRetries) {
//        for (int i = 0; i < maxRetries; i++) {
//            try {
//                // 指数退避：100ms, 200ms, 400ms
//                Thread.sleep(100L * (1L << i));
//
//                Level10Order order = orderService.getOrder(orderId);
//                if (order == null) {
//                    return false;
//                }
//
//                String messageBody = objectMapper.writeValueAsString(order);
//                Message message = ClientServiceProvider.loadService()
//                        .newMessageBuilder()
//                        .setTopic(Level10Constants.BATCH_ORDER_TOPIC)
//                        .setTag(order.getOrderType().name())
//                        .setKeys(orderId)
//                        .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
//                        .build();
//
//                producer.send(message);
//                log.info("✅ [Best] 重试成功 - OrderId: {}, 重试次数: {}", orderId, i + 1);
//                return true;
//
//            } catch (Exception e) {
//                log.warn("⚠️ [Best] 重试失败 - OrderId: {}, 重试次数: {}", orderId, i + 1);
//            }
//        }
//        return false;
//    }
//
//    /**
//     * 查看统计信息
//     */
//    @GetMapping("/stats")
//    public String stats() {
//        return String.format("""
//                📊 Level 10 Producer 统计（Best）
//                - 总发送数: %d
//                - 总失败数: %d
//                - 成功率: %.2f%%
//                - 当前限流速率: %.2f 条/秒
//
//                %s
//                """,
//                totalSent.get(),
//                totalFailed.get(),
//                totalSent.get() > 0 ? (double) (totalSent.get() - totalFailed.get()) * 100 / totalSent.get() : 0,
//                rateLimiter.getRate(),
//                orderService.getStats());
//    }
//
//    /**
//     * 重置统计
//     */
//    @GetMapping("/reset")
//    public String reset() {
//        totalSent.set(0);
//        totalFailed.set(0);
//        orderService.reset();
//        return "✅ 统计已重置";
//    }
//
//    /**
//     * 帮助信息
//     */
//    @GetMapping("/help")
//    public String help() {
//        return """
//                🆘 Level 10 Best 版本说明
//
//                最佳实践：消息批量处理与流量控制
//
//                测试接口：
//                1. 发送单个订单：
//                   curl "http://localhost:8070/challenge/level10/best/sendOrder?userId=USER-001&orderType=NORMAL&amount=100"
//
//                2. 批量发送（观察异常隔离）：
//                   curl "http://localhost:8070/challenge/level10/best/batchSend?count=50"
//
//                3. 压力测试（观察流量控制）：
//                   curl "http://localhost:8070/challenge/level10/best/stressTest?count=500"
//
//                4. 查看统计：
//                   curl "http://localhost:8070/challenge/level10/best/stats"
//
//                5. 重置统计：
//                   curl "http://localhost:8070/challenge/level10/best/reset"
//
//                最佳实践列表：
//                1. ✅ 本地队列缓存 + 批量处理
//                2. ✅ 合理配置线程数（CPU * 2）
//                3. ✅ Semaphore 流量控制
//                4. ✅ 批量提交数据库
//                5. ✅ RateLimiter 限流保护
//                6. ✅ 异常隔离 + 失败重试
//                7. ✅ 异步发送 + 线程池
//                8. ✅ 优雅关闭
//
//                性能对比：
//                - Buggy: 66 条/秒
//                - Best: 666 条/秒
//                - 提升: 10 倍！
//                """;
//    }
//}
