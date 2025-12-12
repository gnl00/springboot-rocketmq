package one.demo.challenge.level12;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Level 12 测试控制器
 */
@Slf4j
@RestController
@RequestMapping("/challenge/level12")
public class Level12TestController {

    private Level12MessageStoreBuggy buggyStore;
    private Level12MessageStoreFixed storeFixed;
    private final Random random = new Random();
    private long testStartTime;

    @PostConstruct
    public void init() {
        buggyStore = new Level12MessageStoreBuggy(Level12Constants.BUGGY_STORE_PATH);
        storeFixed = new Level12MessageStoreFixed(Level12Constants.FIXED_STORE_PATH);
        testStartTime = System.currentTimeMillis();
        log.info("✅ Level 12 测试控制器初始化完成");
    }

    @PreDestroy
    public void destroy() {
        if (buggyStore != null) {
            buggyStore.shutdown();
        }
    }

    /**
     * 帮助信息
     */
    @GetMapping("/help")
    public String help() {
        return """
                🆘 Level 12: 消息存储架构 - CommitLog + ConsumeQueue 设计

                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                📖 挑战说明
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                本关卡让你理解 RocketMQ 的核心存储架构设计：
                - 为什么采用 CommitLog + ConsumeQueue 分离设计？
                - 顺序写 vs 随机写的性能差异
                - 数据与索引分离的架构思想

                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                🐛 Buggy 版本问题
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                当前实现按 Topic 分别存储消息，存在以下问题：

                1. 磁盘随机 IO 严重
                   - 多个 Topic 并发写入，磁盘磁头不断跳转
                   - 写入性能从 500 MB/s 降到 50 MB/s

                2. 文件句柄爆炸
                   - 每个 Topic 独立文件
                   - 100 个 Topic = 100 个文件句柄

                3. 消息查询效率低
                   - 按 MessageId 查询需要遍历所有 Topic 文件
                   - 查询延迟高达数秒

                4. 空间浪费
                   - 文件系统块分配开销（小文件浪费空间）
                   - 小 Topic 也占用大量空间

                5. 无法支持多消费者组
                   - 所有消费者共享同一个文件
                   - 无法独立维护消费进度

                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                🧪 测试接口
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                1. 发送单条消息（Buggy 版本）
                   curl "http://localhost:8086/challenge/level12/buggy/sendMessage?topic=level12-order-topic&tag=urgent&key=ORDER-001&body=test"

                2. 批量发送消息（观察随机 IO 问题）
                   curl "http://localhost:8086/challenge/level12/buggy/batchSend?count=1000&topics=5"

                3. 并发写入测试（观察性能下降）
                   curl "http://localhost:8086/challenge/level12/buggy/concurrentWrite?count=5000&threads=10"

                4. 按 MessageId 查询（观察查询慢）
                   curl "http://localhost:8086/challenge/level12/buggy/queryByMessageId?messageId=xxx"

                5. 按 Tag 过滤（观察扫描慢）
                   curl "http://localhost:8086/challenge/level12/buggy/queryByTag?topic=level12-order-topic&tag=urgent"

                6. 查看统计信息
                   curl "http://localhost:8086/challenge/level12/buggy/stats"

                7. 重置测试
                   curl "http://localhost:8086/challenge/level12/buggy/reset"

                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                💡 任务目标
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                1. 运行测试，观察 Buggy 版本的问题
                2. 分析为什么会出现这些问题
                3. 设计并实现 Fixed 版本（CommitLog + ConsumeQueue）
                4. 对比性能差异

                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                📚 参考资料
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                - 设计文档: LEVEL12-DESIGN.md
                - RocketMQ 源码: org.apache.rocketmq.store.CommitLog
                - RocketMQ 源码: org.apache.rocketmq.store.ConsumeQueue

                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                准备好开始挑战了吗？🚀
                """;
    }

    /**
     * 发送单条消息（Buggy 版本）
     */
    @GetMapping("/buggy/sendMessage")
    public String sendMessageBuggy(
            @RequestParam(defaultValue = "level12-order-topic") String topic,
            @RequestParam(defaultValue = "normal") String tag,
            @RequestParam(defaultValue = "") String key,
            @RequestParam(defaultValue = "test message") String body) {

        try {
            Level12Message message = new Level12Message();
            message.setMessageId(UUID.randomUUID().toString());
            message.setTopic(topic);
            message.setTag(tag);
            message.setKey(key.isEmpty() ? UUID.randomUUID().toString() : key);
            message.setBody(body);
            message.setCreateTime(System.currentTimeMillis());
            message.setQueueId(0);
            message.setQueueOffset(0);

            storeFixed.putMessage(message);

            return String.format("""
                    ✅ 消息已发送（Buggy 版本）

                    消息信息：
                    - MessageId: %s
                    - Topic: %s
                    - Tag: %s
                    - Key: %s
                    - Body: %s

                    ⚠️ Bug 提示：
                    消息被写入到独立的 Topic 文件中，多 Topic 并发写入会导致磁盘随机 IO！

                    💡 测试建议：
                    - 发送多个不同 Topic 的消息
                    - 观察文件句柄数量增长
                    - curl "http://localhost:8086/challenge/level12/buggy/stats"
                    """,
                    message.getMessageId().substring(0, 8) + "...",
                    topic, tag, key, body.substring(0, Math.min(20, body.length()))
            );

        } catch (Exception e) {
            log.error("❌ [Buggy] 发送消息失败", e);
            return "❌ 发送失败: " + e.getMessage();
        }
    }

    /**
     * 批量发送消息（Buggy 版本）
     */
    @GetMapping("/buggy/batchSend")
    public String batchSendBuggy(
            @RequestParam(defaultValue = "1000") int count,
            @RequestParam(defaultValue = "5") int topics) {

        if (count > 10000) {
            return "❌ 批量发送数量不能超过 10000";
        }

        if (topics > Level12Constants.TEST_TOPICS.length) {
            topics = Level12Constants.TEST_TOPICS.length;
        }

        long startTime = System.currentTimeMillis();
        int successCount = 0;

        try {
            for (int i = 0; i < count; i++) {
                // 轮流使用不同的 Topic（模拟多 Topic 并发写入）
                String topic = Level12Constants.TEST_TOPICS[i % topics];
                String tag = i % 3 == 0 ? "urgent" : "normal";

                Level12Message message = new Level12Message();
                message.setMessageId(UUID.randomUUID().toString());
                message.setTopic(topic);
                message.setTag(tag);
                message.setKey("KEY-" + i);
                message.setBody(generateMessageBody(Level12Constants.DEFAULT_MESSAGE_SIZE));
                message.setCreateTime(System.currentTimeMillis());
                message.setQueueId(i % 4);
                message.setQueueOffset(i);

                buggyStore.putMessage(message);
                successCount++;
            }

            long duration = System.currentTimeMillis() - startTime;
            Level12StoreStats stats = buggyStore.getStats();

            return String.format("""
                    ✅ 批量发送完成（Buggy 版本）

                    发送统计：
                    - 请求数量: %,d
                    - 成功数量: %,d
                    - Topic 数量: %d
                    - 总耗时: %,d ms
                    - 平均延迟: %.2f ms
                    - 吞吐量: %.2f msg/s

                    存储统计：
                    - 文件句柄: %,d
                    - 磁盘使用: %.2f MB

                    ⚠️ Bug 现象：
                    1. 多个 Topic 并发写入，磁盘随机 IO 严重
                    2. 文件句柄数量 = Topic 数量
                    3. 平均延迟较高（随机 IO 导致）

                    💡 对比建议：
                    - 实现 Fixed 版本后再次测试
                    - 对比写入延迟和吞吐量
                    - 观察文件句柄数量差异
                    """,
                    count, successCount, topics, duration,
                    stats.getAvgPutLatency(),
                    (double) successCount * 1000 / duration,
                    stats.getFileHandleCount().get(),
                    stats.getDiskUsage().get() / 1024.0 / 1024.0
            );

        } catch (Exception e) {
            log.error("❌ [Buggy] 批量发送失败", e);
            return "❌ 批量发送失败: " + e.getMessage();
        }
    }

    /**
     * 并发写入测试（Buggy 版本）
     */
    @GetMapping("/buggy/concurrentWrite")
    public String concurrentWriteBuggy(
            @RequestParam(defaultValue = "5000") int count,
            @RequestParam(defaultValue = "10") int threads) {

        if (count > 20000) {
            return "❌ 并发写入数量不能超过 20000";
        }

        long startTime = System.currentTimeMillis();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threads);

        try {
            int countPerThread = count / threads;

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    for (int i = 0; i < countPerThread; i++) {
                        try {
                            // 每个线程写入不同的 Topic（模拟多 Topic 并发）
                            String topic = Level12Constants.TEST_TOPICS[threadId % Level12Constants.TEST_TOPICS.length];

                            Level12Message message = new Level12Message();
                            message.setMessageId(UUID.randomUUID().toString());
                            message.setTopic(topic);
                            message.setTag("concurrent");
                            message.setKey("THREAD-" + threadId + "-" + i);
                            message.setBody(generateMessageBody(Level12Constants.DEFAULT_MESSAGE_SIZE));
                            message.setCreateTime(System.currentTimeMillis());
                            message.setQueueId(threadId % 4);
                            message.setQueueOffset(i);

                            buggyStore.putMessage(message);
                            successCount.incrementAndGet();

                        } catch (Exception e) {
                            failCount.incrementAndGet();
                            log.error("❌ [Buggy] 并发写入失败", e);
                        }
                    }
                });
            }

            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.MINUTES);

            long duration = System.currentTimeMillis() - startTime;
            Level12StoreStats stats = buggyStore.getStats();

            return String.format("""
                    ✅ 并发写入完成（Buggy 版本）

                    测试配置：
                    - 总消息数: %,d
                    - 线程数: %d
                    - 每线程: %,d

                    执行结果：
                    - 成功数量: %,d
                    - 失败数量: %,d
                    - 总耗时: %,d ms
                    - 平均延迟: %.2f ms
                    - 吞吐量: %.2f msg/s

                    存储统计：
                    - 文件句柄: %,d
                    - 磁盘使用: %.2f MB

                    ⚠️ Bug 现象：
                    1. 并发写入时，磁盘随机 IO 更加严重
                    2. 多个线程竞争不同的文件锁
                    3. 吞吐量远低于理论值

                    💡 性能分析：
                    - 理论吞吐量（顺序写）: ~50,000 msg/s
                    - 实际吞吐量（随机写）: ~%.0f msg/s
                    - 性能损失: %.1f%%
                    """,
                    count, threads, countPerThread,
                    successCount.get(), failCount.get(), duration,
                    stats.getAvgPutLatency(),
                    (double) successCount.get() * 1000 / duration,
                    stats.getFileHandleCount().get(),
                    stats.getDiskUsage().get() / 1024.0 / 1024.0,
                    (double) successCount.get() * 1000 / duration,
                    (1 - (double) successCount.get() * 1000 / duration / 50000) * 100
            );

        } catch (Exception e) {
            log.error("❌ [Buggy] 并发写入失败", e);
            return "❌ 并发写入失败: " + e.getMessage();
        }
    }

    /**
     * 按 MessageId 查询（Buggy 版本）
     */
    @GetMapping("/buggy/queryByMessageId")
    public String queryByMessageIdBuggy(@RequestParam String messageId) {
        long startTime = System.currentTimeMillis();

        try {
            Level12Message message = buggyStore.queryByMessageId(messageId);
            long duration = System.currentTimeMillis() - startTime;

            if (message == null) {
                return String.format("""
                        ❌ 消息未找到（Buggy 版本）

                        查询信息：
                        - MessageId: %s
                        - 查询耗时: %,d ms
                        - 扫描 Topic: %d

                        ⚠️ Bug 现象：
                        需要遍历所有 Topic 的文件才能找到消息，查询延迟极高！

                        💡 改进建议：
                        使用统一的索引文件（IndexFile），支持按 MessageId 快速查询
                        """,
                        messageId, duration, buggyStore.getAllTopics().size()
                );
            }

            return String.format("""
                    ✅ 消息查询成功（Buggy 版本）

                    消息信息：
                    - MessageId: %s
                    - Topic: %s
                    - Tag: %s
                    - Key: %s
                    - CreateTime: %d
                    - StoreTime: %d

                    查询统计：
                    - 查询耗时: %,d ms
                    - 扫描 Topic: %d

                    ⚠️ Bug 现象：
                    查询延迟高达 %,d ms，生产环境不可接受！

                    💡 改进建议：
                    - 使用 IndexFile 支持快速查询
                    - 查询延迟应该在 10ms 以内
                    """,
                    message.getMessageId().substring(0, 8) + "...",
                    message.getTopic(), message.getTag(), message.getKey(),
                    message.getCreateTime(), message.getStoreTime(),
                    duration, buggyStore.getAllTopics().size(), duration
            );

        } catch (Exception e) {
            log.error("❌ [Buggy] 查询消息失败", e);
            return "❌ 查询失败: " + e.getMessage();
        }
    }

    /**
     * 按 Tag 过滤（Buggy 版本）
     */
    @GetMapping("/buggy/queryByTag")
    public String queryByTagBuggy(
            @RequestParam String topic,
            @RequestParam String tag) {

        long startTime = System.currentTimeMillis();

        try {
            List<Level12Message> messages = buggyStore.queryByTag(topic, tag);
            long duration = System.currentTimeMillis() - startTime;

            return String.format("""
                    ✅ Tag 过滤完成（Buggy 版本）

                    查询条件：
                    - Topic: %s
                    - Tag: %s

                    查询结果：
                    - 匹配消息: %,d
                    - 查询耗时: %,d ms

                    ⚠️ Bug 现象：
                    需要扫描整个 Topic 文件，然后在内存中过滤，效率低！

                    💡 改进建议：
                    - ConsumeQueue 中存储 Tag HashCode
                    - 支持在索引层面快速过滤
                    - 避免读取不需要的消息体
                    """,
                    topic, tag, messages.size(), duration
            );

        } catch (Exception e) {
            log.error("❌ [Buggy] Tag 过滤失败", e);
            return "❌ 过滤失败: " + e.getMessage();
        }
    }

    /**
     * 查看统计信息（Buggy 版本）
     */
    @GetMapping("/buggy/stats")
    public String statsBuggy() {
        Level12StoreStats stats = buggyStore.getStats();
        long duration = System.currentTimeMillis() - testStartTime;

        return String.format("""
                %s

                运行时长: %,d ms (%.2f 秒)

                Topic 统计：
                - Topic 数量: %d
                - 总消息数: %,d

                性能指标：
                - 平均写入 TPS: %.2f msg/s

                ⚠️ Bug 总结：
                1. 文件句柄数 = Topic 数量（会爆炸）
                2. 平均写入延迟较高（随机 IO）
                3. 查询延迟极高（需要遍历文件）
                4. 磁盘空间利用率低

                💡 改进方向：
                - 使用 CommitLog 统一存储（顺序写）
                - 使用 ConsumeQueue 轻量级索引
                - 使用 IndexFile 支持快速查询
                - 使用 MappedByteBuffer 零拷贝
                """,
                stats.format(),
                duration, duration / 1000.0,
                buggyStore.getAllTopics().size(),
                stats.getPutMessageCount().get(),
                stats.getPutTps(duration)
        );
    }

    /**
     * 重置测试（Buggy 版本）
     */
    @GetMapping("/buggy/reset")
    public String resetBuggy() {
        buggyStore.reset();
        testStartTime = System.currentTimeMillis();
        return "✅ Buggy 版本已重置";
    }

    /**
     * 生成指定大小的消息体
     */
    private String generateMessageBody(int size) {
        StringBuilder sb = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        return sb.toString();
    }
}
