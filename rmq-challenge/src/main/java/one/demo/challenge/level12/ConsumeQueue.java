package one.demo.challenge.level12;

import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * ConsumeQueue - 消费队列索引
 *
 * 核心特性：
 * 1. 每个 Topic-Queue 一个 ConsumeQueue
 * 2. 只存储索引信息，不存储消息体
 * 3. 每条索引固定 20 字节：CommitLog Offset(8) + Size(4) + Tag HashCode(8)
 * 4. 支持按 Tag 快速过滤
 */
@Slf4j
public class ConsumeQueue {

    // 每条索引的大小：20 字节
    public static final int CQ_STORE_UNIT_SIZE = 20;

    // 单个文件大小：30 万条索引 = 6MB
    private static final int MAPPED_FILE_SIZE = 300000 * CQ_STORE_UNIT_SIZE;

    // Topic 名称
    private final String topic;

    // Queue ID
    private final int queueId;

    // 存储路径
    private final String storePath;

    // MappedFile 队列
    private final MappedFileQueue mappedFileQueue;

    /**
     * 构造函数
     *
     * @param storePath 存储根路径
     * @param topic Topic 名称
     * @param queueId Queue ID
     */
    public ConsumeQueue(String storePath, String topic, int queueId) {
        this.topic = topic;
        this.queueId = queueId;
        this.storePath = storePath + "/consumequeue/" + topic + "/" + queueId;
        this.mappedFileQueue = new MappedFileQueue(this.storePath, MAPPED_FILE_SIZE);

        log.info("✅ ConsumeQueue 初始化完成: topic={}, queueId={}", topic, queueId);
    }

    /**
     * 添加索引
     *
     * @param commitLogOffset CommitLog 物理偏移量
     * @param size 消息大小
     * @param tagsCode Tag HashCode
     */
    public void putMessagePositionInfo(long commitLogOffset, int size, long tagsCode) {
        try {
            // 构建索引：CommitLog Offset(8) + Size(4) + Tag HashCode(8)
            ByteBuffer buffer = ByteBuffer.allocate(CQ_STORE_UNIT_SIZE);
            buffer.putLong(commitLogOffset);
            buffer.putInt(size);
            buffer.putLong(tagsCode);
            buffer.flip();

            // 写入索引
            long offset = mappedFileQueue.append(buffer);

            if (offset == -1) {
                log.error("❌ ConsumeQueue 写入失败: topic={}, queueId={}", topic, queueId);
            } else {
                log.debug("📝 ConsumeQueue 写入成功: topic={}, queueId={}, offset={}",
                    topic, queueId, offset);
            }

        } catch (Exception e) {
            log.error("❌ ConsumeQueue 添加索引失败: topic={}, queueId={}", topic, queueId, e);
        }
    }

    /**
     * 读取索引
     *
     * @param index 索引位置（逻辑偏移量，从 0 开始）
     * @return 索引缓冲区
     */
    public ByteBuffer getIndexBuffer(long index) {
        // 计算物理偏移量
        long position = index * CQ_STORE_UNIT_SIZE;

        // 读取索引
        return mappedFileQueue.getData(position, CQ_STORE_UNIT_SIZE);
    }

    /**
     * 批量读取索引
     *
     * @param startIndex 起始索引
     * @param maxCount 最大数量
     * @return 索引列表
     */
    public List<CQUnit> getIndexList(long startIndex, int maxCount) {
        List<CQUnit> result = new ArrayList<>();

        long maxIndex = getMaxIndex();

        for (long i = startIndex; i < maxIndex && result.size() < maxCount; i++) {
            ByteBuffer buffer = getIndexBuffer(i);
            if (buffer == null) {
                break;
            }

            long commitLogOffset = buffer.getLong();
            int size = buffer.getInt();
            long tagsCode = buffer.getLong();

            result.add(new CQUnit(commitLogOffset, size, tagsCode));
        }

        return result;
    }

    /**
     * 按 Tag 过滤索引
     *
     * @param startIndex 起始索引
     * @param maxCount 最大数量
     * @param tagsCode Tag HashCode（0 表示不过滤）
     * @return 索引列表
     */
    public List<CQUnit> filterByTag(long startIndex, int maxCount, long tagsCode) {
        List<CQUnit> result = new ArrayList<>();

        long maxIndex = getMaxIndex();

        for (long i = startIndex; i < maxIndex && result.size() < maxCount; i++) {
            ByteBuffer buffer = getIndexBuffer(i);
            if (buffer == null) {
                break;
            }

            long commitLogOffset = buffer.getLong();
            int size = buffer.getInt();
            long tag = buffer.getLong();

            // Tag 过滤
            if (tagsCode == 0 || tag == tagsCode) {
                result.add(new CQUnit(commitLogOffset, size, tag));
            }
        }

        return result;
    }

    /**
     * 获取最大索引位置
     */
    public long getMaxIndex() {
        long maxOffset = mappedFileQueue.getMaxOffset();
        return maxOffset / CQ_STORE_UNIT_SIZE;
    }

    /**
     * 获取最小索引位置
     */
    public long getMinIndex() {
        long minOffset = mappedFileQueue.getMinOffset();
        return minOffset / CQ_STORE_UNIT_SIZE;
    }

    /**
     * 刷盘
     */
    public void flush() {
        mappedFileQueue.flush();
    }

    /**
     * 关闭
     */
    public void shutdown() {
        mappedFileQueue.shutdown();
        log.info("✅ ConsumeQueue 已关闭: topic={}, queueId={}", topic, queueId);
    }

    // Getters

    public String getTopic() {
        return topic;
    }

    public int getQueueId() {
        return queueId;
    }

    public String getStorePath() {
        return storePath;
    }

    // ==================== 内部类 ====================

    /**
     * ConsumeQueue 单元（索引条目）
     */
    public static class CQUnit {
        private final long commitLogOffset;
        private final int size;
        private final long tagsCode;

        public CQUnit(long commitLogOffset, int size, long tagsCode) {
            this.commitLogOffset = commitLogOffset;
            this.size = size;
            this.tagsCode = tagsCode;
        }

        public long getCommitLogOffset() {
            return commitLogOffset;
        }

        public int getSize() {
            return size;
        }

        public long getTagsCode() {
            return tagsCode;
        }

        @Override
        public String toString() {
            return "CQUnit{" +
                "commitLogOffset=" + commitLogOffset +
                ", size=" + size +
                ", tagsCode=" + tagsCode +
                '}';
        }
    }
}
