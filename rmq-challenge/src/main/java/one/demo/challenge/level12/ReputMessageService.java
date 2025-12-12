package one.demo.challenge.level12;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Reput Message Service - 异步构建索引服务
 *
 * 核心职责：
 * 1. 从 CommitLog 读取消息
 * 2. 异步构建 ConsumeQueue 索引
 * 3. 记录已处理的偏移量，支持重启恢复
 */
@Slf4j
public class ReputMessageService {

    // 消息存储
    private final Level12MessageStoreBest messageStore;

    // CommitLog
    private final CommitLog commitLog;

    // 已处理的 CommitLog 偏移量
    private final AtomicLong reputFromOffset = new AtomicLong(0);

    // 是否运行中
    private volatile boolean running = false;

    // 后台线程
    private Thread reputThread;

    /**
     * 构造函数
     *
     * @param messageStore 消息存储
     * @param commitLog CommitLog
     */
    public ReputMessageService(Level12MessageStoreBest messageStore, CommitLog commitLog) {
        this.messageStore = messageStore;
        this.commitLog = commitLog;
    }

    /**
     * 启动服务
     */
    public void start() {
        if (running) {
            log.warn("⚠️ ReputMessageService 已经在运行中");
            return;
        }

        running = true;

        // 初始化偏移量
        long minOffset = commitLog.getMinOffset();
        reputFromOffset.set(minOffset);

        // 启动后台线程
        reputThread = new Thread(this::doReput, "ReputMessageService");
        reputThread.setDaemon(true);
        reputThread.start();

        log.info("✅ ReputMessageService 已启动: startOffset={}", minOffset);
    }

    /**
     * 停止服务
     */
    public void shutdown() {
        running = false;

        if (reputThread != null) {
            try {
                reputThread.interrupt();
                reputThread.join(5000);
            } catch (InterruptedException e) {
                log.error("❌ 停止 ReputMessageService 失败", e);
            }
        }

        log.info("✅ ReputMessageService 已停止");
    }

    /**
     * 执行索引构建
     */
    private void doReput() {
        log.info("🔄 ReputMessageService 开始构建索引");

        while (running) {
            try {
                // 获取当前偏移量
                long currentOffset = reputFromOffset.get();
                long maxOffset = commitLog.getMaxOffset();

                // 如果没有新消息，等待
                if (currentOffset >= maxOffset) {
                    Thread.sleep(100);
                    continue;
                }

                // 读取消息
                Level12Message message = commitLog.getMessage(currentOffset);
                if (message == null) {
                    log.warn("⚠️ 读取消息失败: offset={}", currentOffset);
                    Thread.sleep(100);
                    continue;
                }

                // 构建 ConsumeQueue 索引
                dispatchToConsumeQueue(message);

                // 更新偏移量（消息长度 = 4 字节长度 + 消息体长度）
                int messageLength = 4 + message.getBody().getBytes().length;
                reputFromOffset.addAndGet(messageLength);

                log.debug("🔄 索引构建成功: topic={}, offset={}, nextOffset={}",
                    message.getTopic(), currentOffset, reputFromOffset.get());

            } catch (InterruptedException e) {
                log.info("ReputMessageService 被中断");
                break;
            } catch (Exception e) {
                log.error("❌ ReputMessageService 处理失败", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    break;
                }
            }
        }

        log.info("🔄 ReputMessageService 已退出");
    }

    /**
     * 分发到 ConsumeQueue
     *
     * @param message 消息
     */
    private void dispatchToConsumeQueue(Level12Message message) {
        String topic = message.getTopic();
        int queueId = message.getQueueId();
        long commitLogOffset = message.getPhysicalOffset();
        int size = message.getBody().getBytes().length + 4; // 4 字节长度 + 消息体
        long tagsCode = message.getTag() != null ? message.getTag().hashCode() : 0;

        // 获取或创建 ConsumeQueue
        ConsumeQueue consumeQueue = messageStore.findConsumeQueue(topic, queueId);

        // 添加索引
        consumeQueue.putMessagePositionInfo(commitLogOffset, size, tagsCode);
    }

    /**
     * 手动触发索引构建（用于测试）
     */
    public void doReputOnce() {
        try {
            long currentOffset = reputFromOffset.get();
            long maxOffset = commitLog.getMaxOffset();

            if (currentOffset >= maxOffset) {
                log.info("ℹ️ 没有新消息需要构建索引");
                return;
            }

            // 读取消息
            Level12Message message = commitLog.getMessage(currentOffset);
            if (message == null) {
                log.warn("⚠️ 读取消息失败: offset={}", currentOffset);
                return;
            }

            // 构建索引
            dispatchToConsumeQueue(message);

            // 更新偏移量
            int messageLength = 4 + message.getBody().getBytes().length;
            reputFromOffset.addAndGet(messageLength);

            log.info("✅ 手动构建索引成功: topic={}, offset={}",
                message.getTopic(), currentOffset);

        } catch (Exception e) {
            log.error("❌ 手动构建索引失败", e);
        }
    }

    // Getters

    public long getReputFromOffset() {
        return reputFromOffset.get();
    }

    public boolean isRunning() {
        return running;
    }
}
