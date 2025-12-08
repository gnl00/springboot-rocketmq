package one.demo.challenge.level10;

/**
 * Level 10 常量定义
 */
public class Level10Constants {
    public static final String ENDPOINTS = "localhost:8081";
    public static final String BATCH_ORDER_TOPIC = "batch-order-topic";
    public static final String CONSUMER_GROUP = "batch-order-consumer-group";

    // 批量处理相关常量
    public static final int DEFAULT_BATCH_SIZE = 10;
    public static final int MAX_BATCH_SIZE = 100;
    public static final long BATCH_TIMEOUT_MS = 5000;
}
