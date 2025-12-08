//package one.demo.challenge.level10;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.rocketmq.client.annotation.RocketMQMessageListener;
//import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
//import org.apache.rocketmq.client.apis.message.MessageView;
//import org.apache.rocketmq.client.core.RocketMQListener;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Component;
//
//import jakarta.annotation.PostConstruct;
//import jakarta.annotation.PreDestroy;
//import java.nio.charset.StandardCharsets;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.concurrent.*;
//import java.util.concurrent.atomic.AtomicInteger;
//
///**
// * Level 10 消费者（Best 版本）
// *
// * 最佳实践：
// * 1. ✅ 使用本地队列缓存消息，实现批量处理
// * 2. ✅ 配置合理的线程数，充分利用 CPU
// * 3. ✅ 使用 Semaphore 实现流量控制，防止 OOM
// * 4. ✅ 批量提交数据库，减少 IO 次数
// * 5. ✅ 异步处理 + 批量聚合，提升吞吐量
// * 6. ✅ 优雅关闭，确保消息不丢失
// *
// * 性能对比：
// * - Buggy 版本：逐条处理，15ms/条，66 条/秒
// * - Best 版本：批量处理，1.5ms/条，666 条/秒（10倍提升）
// */
//@Slf4j
//@Component
//@RocketMQMessageListener(
//        topic = Level10Constants.BATCH_ORDER_TOPIC,
//        consumerGroup = Level10Constants.CONSUMER_GROUP + "-best",
//        endpoints = Level10Constants.ENDPOINTS,
//        tag = "*",
//        // 最佳实践 1: 合理配置线程数（CPU 核心数 * 2）
//        consumptionThreadCount = 8,
//        // 最佳实践 2: 配置批量拉取大小，减少网络 IO
//        maxCachedMessageCount = 32
//)
//public class Level10ConsumerBest implements RocketMQListener {
//
//    @Autowired
//    private Level10OrderService orderService;
//
//    private final ObjectMapper objectMapper = new ObjectMapper()
//            .findAndRegisterModules();
//
//    // 最佳实践 3: 本地队列缓存消息，用于批量处理
//    private final BlockingQueue<Level10Order> orderQueue = new LinkedBlockingQueue<>(1000);
//
//    // 最佳实践 4: 使用 Semaphore 实现流量控制
//    private final Semaphore rateLimiter = new Semaphore(500); // 最多同时处理 500 条消息
//
//    // 最佳实践 5: 批量处理线程池
//    private ScheduledExecutorService batchProcessor;
//
//    // 最佳实践 6: 使用 AtomicBoolean 防止并发触发批量处理
//    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
//
//    // 统计信息
//    private final AtomicInteger receivedCount = new AtomicInteger(0);
//    private final AtomicInteger processedCount = new AtomicInteger(0);
//    private final AtomicInteger batchCount = new AtomicInteger(0);
//
//    private volatile boolean running = true;
//
//    @PostConstruct
//    public void init() {
//        // 启动批量处理线程
//        batchProcessor = Executors.newSingleThreadScheduledExecutor(r -> {
//            Thread t = new Thread(r, "batch-processor");
//            t.setDaemon(false); // 非守护线程，确保优雅关闭
//            return t;
//        });
//
//        // 最佳实践 7: 定时批量处理（每 1 秒或达到 10 条时触发）
//        batchProcessor.scheduleWithFixedDelay(
//                this::processBatchSafely,
//                1000,
//                1000,
//                TimeUnit.MILLISECONDS
//        );
//
//        log.info("✅ [Best] Level 10 消费者初始化完成 - 批量处理已启动");
//    }
//
//    @PreDestroy
//    public void destroy() {
//        running = false;
//
//        log.info("🛑 [Best] 开始优雅关闭...");
//
//        // 最佳实践 8: 优雅关闭，处理完剩余消息
//        if (batchProcessor != null) {
//            batchProcessor.shutdown();
//            try {
//                if (!batchProcessor.awaitTermination(30, TimeUnit.SECONDS)) {
//                    log.warn("⚠️ [Best] 批量处理线程未在 30 秒内完成，强制关闭");
//                    batchProcessor.shutdownNow();
//                }
//            } catch (InterruptedException e) {
//                batchProcessor.shutdownNow();
//                Thread.currentThread().interrupt();
//            }
//        }
//
//        // 处理剩余消息
//        if (!orderQueue.isEmpty()) {
//            log.info("🔄 [Best] 处理剩余 {} 条消息", orderQueue.size());
//            processBatchSafely();
//        }
//
//        log.info("✅ [Best] 优雅关闭完成 - 接收: {}, 处理: {}, 批次: {}",
//                receivedCount.get(), processedCount.get(), batchCount.get());
//    }
//
//    @Override
//    public ConsumeResult consume(MessageView messageView) {
//        if (!running) {
//            return ConsumeResult.FAILURE;
//        }
//
//        String body = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();
//
//        try {
//            // 最佳实践 8: 流量控制，防止 OOM
//            if (!rateLimiter.tryAcquire(100, TimeUnit.MILLISECONDS)) {
//                log.warn("⚠️ [Best] 流量控制触发，消息将重试 - MessageId: {}",
//                        messageView.getMessageId());
//                return ConsumeResult.FAILURE;
//            }
//
//            Level10Order order = objectMapper.readValue(body, Level10Order.class);
//            receivedCount.incrementAndGet();
//
//            // 最佳实践 9: 放入本地队列，异步批量处理
//            boolean offered = orderQueue.offer(order, 100, TimeUnit.MILLISECONDS);
//            if (!offered) {
//                log.warn("⚠️ [Best] 本地队列已满，消息将重试 - OrderId: {}", order.getOrderId());
//                rateLimiter.release();
//                return ConsumeResult.FAILURE;
//            }
//
//            // 最佳实践 10: 达到批量阈值时，立即触发处理（使用 CAS 防止并发）
//            if (orderQueue.size() >= Level10Constants.DEFAULT_BATCH_SIZE) {
//                triggerBatchProcessing();
//            }
//
//            log.debug("📥 [Best] 消息已入队 - OrderId: {}, 队列大小: {}",
//                    order.getOrderId(), orderQueue.size());
//
//            return ConsumeResult.SUCCESS;
//
//        } catch (Exception e) {
//            log.error("❌ [Best] 消息处理失败", e);
//            rateLimiter.release();
//            return ConsumeResult.FAILURE;
//        }
//    }
//
//    /**
//     * 安全触发批量处理（防止并发）
//     * 最佳实践：使用 CAS 确保同一时刻只有一个批量处理任务在执行
//     */
//    private void triggerBatchProcessing() {
//        // 使用 CAS 操作，只有当 isProcessing 为 false 时才提交任务
//        if (isProcessing.compareAndSet(false, true)) {
//            batchProcessor.execute(this::processBatchSafely);
//        }
//    }
//
//    /**
//     * 安全的批量处理包装方法
//     */
//    private void processBatchSafely() {
//        try {
//            processBatch();
//        } finally {
//            // 处理完成后重置标志，允许下次触发
//            isProcessing.set(false);
//        }
//    }
//
//    /**
//     * 批量处理订单
//     * 最佳实践：批量从队列取出消息，批量提交数据库
//     */
//    private void processBatch() {
//        if (orderQueue.isEmpty()) {
//            return;
//        }
//
//        List<Level10Order> batch = new ArrayList<>();
//        List<String> orderIds = new ArrayList<>();
//
//        try {
//            // 最佳实践 11: 批量取出消息（最多 100 条）
//            orderQueue.drainTo(batch, Level10Constants.MAX_BATCH_SIZE);
//
//            if (batch.isEmpty()) {
//                return;
//            }
//
//            long startTime = System.currentTimeMillis();
//
//            // 最佳实践 12: 批量处理业务逻辑
//            for (Level10Order order : batch) {
//                orderIds.add(order.getOrderId());
//            }
//
//            // 最佳实践 13: 批量提交数据库（一次 IO 完成多条记录）
//            orderService.batchProcessOrders(orderIds);
//
//            long duration = System.currentTimeMillis() - startTime;
//            int batchSize = batch.size();
//
//            processedCount.addAndGet(batchSize);
//            batchCount.incrementAndGet();
//
//            log.info("✅ [Best] 批量处理完成 - 批次: {}, 数量: {}, 耗时: {}ms, 平均: {:.2f}ms/条",
//                    batchCount.get(), batchSize, duration, (double) duration / batchSize);
//
//            // 最佳实践 14: 释放流量控制许可
//            rateLimiter.release(batchSize);
//
//        } catch (Exception e) {
//            log.error("❌ [Best] 批量处理失败 - 批次大小: {}", batch.size(), e);
//
//            // 最佳实践 15: 异常处理 - 将失败的消息重新放回队列
//            for (Level10Order order : batch) {
//                try {
//                    orderQueue.offer(order, 1, TimeUnit.SECONDS);
//                } catch (InterruptedException ie) {
//                    Thread.currentThread().interrupt();
//                    log.error("❌ [Best] 消息重新入队失败 - OrderId: {}", order.getOrderId());
//                }
//            }
//
//            rateLimiter.release(batch.size());
//        }
//    }
//
//    /**
//     * 获取统计信息
//     */
//    public String getStats() {
//        return String.format("""
//                📊 Level 10 消费者统计（Best）
//                - 接收消息数: %d
//                - 处理消息数: %d
//                - 批量处理次数: %d
//                - 队列大小: %d
//                - 可用许可: %d
//                - 平均批次大小: %.2f
//                """,
//                receivedCount.get(),
//                processedCount.get(),
//                batchCount.get(),
//                orderQueue.size(),
//                rateLimiter.availablePermits(),
//                batchCount.get() > 0 ? (double) processedCount.get() / batchCount.get() : 0);
//    }
//}
