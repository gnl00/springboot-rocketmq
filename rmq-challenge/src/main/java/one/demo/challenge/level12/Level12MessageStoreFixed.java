package one.demo.challenge.level12;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 问题：按 Topic 分别存储消息
 *
 * Bug 列表：
 * 1. 磁盘随机 IO 严重 - 多个 Topic 并发写入，导致磁盘磁头不断跳转
 * 2. 文件句柄爆炸 - 每个 Topic 独立文件，Topic 多时文件句柄数量爆炸
 * 3. 消息查询效率低 - 按 MessageId 查询需要遍历所有 Topic 文件
 * 4. 空间浪费 - 文件系统块分配开销，每个文件至少占用一个块（4KB），小 Topic 浪费空间
 * 5. 无法支持多消费者组 - 所有消费者共享同一个文件
 */
@Slf4j
public class Level12MessageStoreFixed {

    private final String storePath;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Level12StoreStats stats = new Level12StoreStats();

    // Bug 1: 每个 Topic 独立的文件通道，导致文件句柄爆炸
    private final Map<String, FileChannel> topicChannels = new ConcurrentHashMap<>();

    private final Map<String, FileChannel> consumeQueueChannels = new ConcurrentHashMap<>();

    // Bug 2: 每个 Topic 独立的锁，但仍然会有磁盘随机 IO
    private final Map<String, ReentrantReadWriteLock> topicLocks = new ConcurrentHashMap<>();

    private final ReentrantReadWriteLock commitLogLock = new ReentrantReadWriteLock();

    // 消息索引（内存中维护，用于快速查询）
    private final Map<String, Level12Message> messageIndex = new ConcurrentHashMap<>();

    // Topic 的消息列表（内存中维护）
    private final Map<String, List<Level12Message>> topicMessages = new ConcurrentHashMap<>();

    private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);

    public Level12MessageStoreFixed(String storePath) {
        this.storePath = storePath;
        initStore();
    }

    /**
     * 初始化存储
     */
    private void initStore() {
        try {
            Path path = Paths.get(storePath);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
            log.info("✅ Fixed 存储初始化完成: {}", storePath);
        } catch (IOException e) {
            log.error("❌ 初始化存储失败", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 存储消息
     * Bug: 每个 Topic 独立文件，多 Topic 并发写入导致磁盘随机 IO
     */
    public void putMessage(Level12Message message) {
        long startTime = System.currentTimeMillis();

        try {
            String topic = message.getTopic();
            Integer queueId = message.getQueueId();

            FileChannel commitLogChannel = getOrCreateCommitLog();

            commitLogLock.writeLock().lock();
            try {
                // 设置存储时间和物理偏移量
                message.setStoreTime(System.currentTimeMillis());
                message.setPhysicalOffset(commitLogChannel.position());

                // 序列化消息
                String json = objectMapper.writeValueAsString(message);
                byte[] data = json.getBytes();

                // 写入消息内容
                commitLogChannel.write(java.nio.ByteBuffer.wrap(data));
                commitLogChannel.force(false); // 强制刷盘

                EXECUTOR_SERVICE.execute(() -> {
                    // TODO update consumeQueue async
                    /*
                    try {
                        FileChannel consumeQueueChannel = getOrCreateConsumeQueue(topic, queueId);
                        consumeQueueChannel.write(java.nio.ByteBuffer.allocate(20).putLong(message.getPhysicalOffset()).flip());
                    } catch (IOException e) {
                        log.error("❌ [Fixed] 存储消息索引失败", e);
                    }
                    */
                });

                // 更新内存索引
                messageIndex.put(message.getMessageId(), message);
                topicMessages.computeIfAbsent(topic, k -> new ArrayList<>()).add(message);

                // 更新统计
                stats.getFileHandleCount().set(topicChannels.size());
                stats.getDiskUsage().addAndGet(data.length + 4);

            } finally {
                commitLogLock.writeLock().unlock();
            }

            long costTime = System.currentTimeMillis() - startTime;
            stats.recordPut(costTime);

            log.debug("📝 [Fixed] 消息已存储 - Topic: {}, MessageId: {}, 耗时: {} ms",
                topic, message.getMessageId(), costTime);

        } catch (Exception e) {
            log.error("❌ [Fixed] 存储消息失败", e);
            throw new RuntimeException(e);
        }
    }

    private FileChannel getOrCreateCommitLog() {
        try {
            Path filePath = Paths.get(storePath, "commitlog.log");

            FileChannel channel = FileChannel.open(filePath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);

            log.info("📂 [Fixed] 创建 commitlog 文件: {}", filePath);
            return channel;

        } catch (IOException e) {
            log.error("❌ [Fixed] 创建 commitlog 文件失败: ", e);
            throw new RuntimeException(e);
        }
    }

    private FileChannel getOrCreateConsumeQueue(String topic, Integer queueId) {
        String key = topic + "-" + queueId;
        return consumeQueueChannels.computeIfAbsent(key, k -> {
            try {
                Path filePath = Paths.get(storePath, key + ".log");

                FileChannel channel = FileChannel.open(filePath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE);

                log.info("📂 [Fixed] 创建 ConsumeQueue 文件: {}", filePath);
                return channel;

            } catch (IOException e) {
                log.error("❌ [Fixed] 创建 ConsumeQueue 文件失败: ", e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 读取消息
     * Bug: 需要知道 Topic 才能读取，效率低
     */
    public Level12Message getMessage(String messageKey, int offset) {
        long startTime = System.currentTimeMillis();

        try {
            FileChannel channel = getOrCreateCommitLog();
            if (channel == null) {
                return null;
            }

            commitLogLock.readLock().lock();
            try {
                // 定位到指定偏移量
                channel.position(offset);

                // 读取消息长度
                Level12Message level12Message = messageIndex.get(messageKey);
                java.nio.ByteBuffer lengthBuffer = java.nio.ByteBuffer.allocate(4);
                channel.read(lengthBuffer);
                lengthBuffer.flip();
                int length = level12Message.getLen();

                // 读取消息内容
                java.nio.ByteBuffer dataBuffer = java.nio.ByteBuffer.allocate(length);
                channel.read(dataBuffer);
                dataBuffer.flip();

                String json = new String(dataBuffer.array());
                Level12Message message = objectMapper.readValue(json, Level12Message.class);

                long costTime = System.currentTimeMillis() - startTime;
                stats.recordGet(costTime);

                return message;

            } finally {
                commitLogLock.readLock().unlock();
            }

        } catch (Exception e) {
            log.error("❌ [Fixed] 读取消息失败", e);
            return null;
        }
    }

    /**
     * 按 MessageId 查询消息
     * Bug: 需要遍历所有 Topic 的文件，效率极低
     */
    public Level12Message queryByMessageId(String messageId) {
        long startTime = System.currentTimeMillis();

        try {
            // Bug 6: 先从内存索引查找（生产环境内存索引可能不完整）
            Level12Message message = messageIndex.get(messageId);
            if (message != null) {
                long costTime = System.currentTimeMillis() - startTime;
                stats.recordQuery(costTime);
                return message;
            }

            // Bug 7: 内存中没有，需要遍历所有 Topic 的文件
            // 这在生产环境中是灾难性的性能问题
            for (String topic : topicChannels.keySet()) {
                List<Level12Message> messages = scanTopicFile(topic);
                for (Level12Message msg : messages) {
                    if (msg.getMessageId().equals(messageId)) {
                        long costTime = System.currentTimeMillis() - startTime;
                        stats.recordQuery(costTime);
                        return msg;
                    }
                }
            }

            long costTime = System.currentTimeMillis() - startTime;
            stats.recordQuery(costTime);

            log.warn("⚠️ [Fixed] 查询消息失败，遍历了 {} 个 Topic 文件，耗时: {} ms",
                topicChannels.size(), costTime);

            return null;

        } catch (Exception e) {
            log.error("❌ [Fixed] 查询消息失败", e);
            return null;
        }
    }

    /**
     * 按 Tag 过滤消息
     * Bug: 需要扫描整个 Topic 文件，效率低
     */
    public List<Level12Message> queryByTag(String topic, String tag) {
        long startTime = System.currentTimeMillis();

        try {
            List<Level12Message> result = new ArrayList<>();

            // Bug 8: 从内存中过滤（生产环境内存可能不够）
            List<Level12Message> messages = topicMessages.get(topic);
            if (messages != null) {
                for (Level12Message message : messages) {
                    if (tag.equals(message.getTag())) {
                        result.add(message);
                    }
                }
            }

            long costTime = System.currentTimeMillis() - startTime;
            stats.recordQuery(costTime);

            return result;

        } catch (Exception e) {
            log.error("❌ [Fixed] 按 Tag 查询失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 扫描 Topic 文件
     * Bug: 全文件扫描，性能极差
     */
    private List<Level12Message> scanTopicFile(String topic) {
        List<Level12Message> messages = new ArrayList<>();

        try {
            FileChannel channel = topicChannels.get(topic);
            if (channel == null) {
                return messages;
            }

            ReentrantReadWriteLock lock = topicLocks.get(topic);
            lock.readLock().lock();
            try {
                channel.position(0);

                while (channel.position() < channel.size()) {
                    // 读取消息长度
                    java.nio.ByteBuffer lengthBuffer = java.nio.ByteBuffer.allocate(4);
                    int read = channel.read(lengthBuffer);
                    if (read < 4) {
                        break;
                    }
                    lengthBuffer.flip();
                    int length = lengthBuffer.getInt();

                    // 读取消息内容
                    java.nio.ByteBuffer dataBuffer = java.nio.ByteBuffer.allocate(length);
                    channel.read(dataBuffer);
                    dataBuffer.flip();

                    String json = new String(dataBuffer.array());
                    Level12Message message = objectMapper.readValue(json, Level12Message.class);
                    messages.add(message);
                }

            } finally {
                lock.readLock().unlock();
            }

        } catch (Exception e) {
            log.error("❌ [Fixed] 扫描 Topic 文件失败: {}", topic, e);
        }

        return messages;
    }

    /**
     * 获取统计信息
     */
    public Level12StoreStats getStats() {
        return stats;
    }

    /**
     * 重置统计
     */
    public void reset() {
        stats.reset();
        messageIndex.clear();
        topicMessages.clear();
    }

    /**
     * 关闭存储
     */
    public void shutdown() {
        try {
            // 关闭所有文件通道
            for (FileChannel channel : topicChannels.values()) {
                channel.close();
            }
            topicChannels.clear();
            log.info("✅ [Fixed] 存储已关闭");
        } catch (IOException e) {
            log.error("❌ [Fixed] 关闭存储失败", e);
        }
    }

    /**
     * 获取所有 Topic
     */
    public Set<String> getAllTopics() {
        return topicChannels.keySet();
    }

    /**
     * 获取 Topic 的消息数量
     */
    public int getTopicMessageCount(String topic) {
        List<Level12Message> messages = topicMessages.get(topic);
        return messages != null ? messages.size() : 0;
    }

    @Data
    static class MessageQueueIndex {
        private Integer queueId;
        private String topic;
        private String messageKey;
        private int offset;
        private int length;
    }
}
