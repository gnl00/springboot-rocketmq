package one.demo.challenge.level11;

/**
 * Level 11 处理模式
 * 用于模拟不同的消息处理场景
 */
public enum Level11ProcessingMode {
    FAST("快速处理", 50),
    NORMAL("正常处理", 200),
    SLOW("慢处理", 1000),
    VERY_SLOW("超慢处理", 3000),
    RANDOM_FAIL("随机失败", 100);

    private final String description;
    private final long processingTimeMs;

    Level11ProcessingMode(String description, long processingTimeMs) {
        this.description = description;
        this.processingTimeMs = processingTimeMs;
    }

    public String getDescription() {
        return description;
    }

    public long getProcessingTimeMs() {
        return processingTimeMs;
    }
}
