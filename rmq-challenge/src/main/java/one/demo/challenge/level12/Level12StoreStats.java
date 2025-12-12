package one.demo.challenge.level12;

import lombok.Data;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Level 12 存储统计
 */
@Data
public class Level12StoreStats {

    /**
     * 写入消息数
     */
    private final AtomicLong putMessageCount = new AtomicLong(0);

    /**
     * 写入总耗时（毫秒）
     */
    private final AtomicLong putMessageTotalTime = new AtomicLong(0);

    /**
     * 读取消息数
     */
    private final AtomicLong getMessageCount = new AtomicLong(0);

    /**
     * 读取总耗时（毫秒）
     */
    private final AtomicLong getMessageTotalTime = new AtomicLong(0);

    /**
     * 查询消息数
     */
    private final AtomicLong queryMessageCount = new AtomicLong(0);

    /**
     * 查询总耗时（毫秒）
     */
    private final AtomicLong queryMessageTotalTime = new AtomicLong(0);

    /**
     * 文件句柄数
     */
    private final AtomicLong fileHandleCount = new AtomicLong(0);

    /**
     * 磁盘使用量（字节）
     */
    private final AtomicLong diskUsage = new AtomicLong(0);

    /**
     * 记录写入
     */
    public void recordPut(long costTime) {
        putMessageCount.incrementAndGet();
        putMessageTotalTime.addAndGet(costTime);
    }

    /**
     * 记录读取
     */
    public void recordGet(long costTime) {
        getMessageCount.incrementAndGet();
        getMessageTotalTime.addAndGet(costTime);
    }

    /**
     * 记录查询
     */
    public void recordQuery(long costTime) {
        queryMessageCount.incrementAndGet();
        queryMessageTotalTime.addAndGet(costTime);
    }

    /**
     * 获取平均写入延迟
     */
    public double getAvgPutLatency() {
        long count = putMessageCount.get();
        if (count == 0) {
            return 0;
        }
        return (double) putMessageTotalTime.get() / count;
    }

    /**
     * 获取平均读取延迟
     */
    public double getAvgGetLatency() {
        long count = getMessageCount.get();
        if (count == 0) {
            return 0;
        }
        return (double) getMessageTotalTime.get() / count;
    }

    /**
     * 获取平均查询延迟
     */
    public double getAvgQueryLatency() {
        long count = queryMessageCount.get();
        if (count == 0) {
            return 0;
        }
        return (double) queryMessageTotalTime.get() / count;
    }

    /**
     * 获取写入 TPS
     */
    public double getPutTps(long durationMs) {
        if (durationMs == 0) {
            return 0;
        }
        return (double) putMessageCount.get() * 1000 / durationMs;
    }

    /**
     * 重置统计
     */
    public void reset() {
        putMessageCount.set(0);
        putMessageTotalTime.set(0);
        getMessageCount.set(0);
        getMessageTotalTime.set(0);
        queryMessageCount.set(0);
        queryMessageTotalTime.set(0);
    }

    /**
     * 格式化输出
     */
    public String format() {
        return String.format("""
                📊 存储统计信息
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                写入统计：
                  - 消息数量: %,d
                  - 平均延迟: %.2f ms
                  - 总耗时: %,d ms

                读取统计：
                  - 消息数量: %,d
                  - 平均延迟: %.2f ms
                  - 总耗时: %,d ms

                查询统计：
                  - 查询次数: %,d
                  - 平均延迟: %.2f ms
                  - 总耗时: %,d ms

                资源统计：
                  - 文件句柄: %,d
                  - 磁盘使用: %.2f MB
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                """,
                putMessageCount.get(),
                getAvgPutLatency(),
                putMessageTotalTime.get(),
                getMessageCount.get(),
                getAvgGetLatency(),
                getMessageTotalTime.get(),
                queryMessageCount.get(),
                getAvgQueryLatency(),
                queryMessageTotalTime.get(),
                fileHandleCount.get(),
                diskUsage.get() / 1024.0 / 1024.0
        );
    }
}
