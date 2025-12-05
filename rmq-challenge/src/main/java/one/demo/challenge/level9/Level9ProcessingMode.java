package one.demo.challenge.level9;

/**
 * 消费端处理模式，用于模拟不同异常场景。
 */
public enum Level9ProcessingMode {
    NORMAL,
    BUSINESS_ERROR,
    SYSTEM_TIMEOUT,
    RANDOM_FAILURE;

    public static Level9ProcessingMode fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return NORMAL;
        }
        for (Level9ProcessingMode mode : values()) {
            if (mode.name().equalsIgnoreCase(raw)) {
                return mode;
            }
        }
        return NORMAL;
    }
}
