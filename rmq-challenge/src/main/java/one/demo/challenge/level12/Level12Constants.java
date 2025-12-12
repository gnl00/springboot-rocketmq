package one.demo.challenge.level12;

/**
 * Level 12 常量定义
 */
public class Level12Constants {

    // RocketMQ 连接地址
    public static final String ENDPOINTS = "localhost:9876";

    // 测试 Topic 列表
    public static final String[] TEST_TOPICS = {
        "level12-order-topic",
        "level12-payment-topic",
        "level12-inventory-topic",
        "level12-notification-topic",
        "level12-log-topic"
    };

    // 存储路径
    public static final String BUGGY_STORE_PATH = "store/level12/buggy";
    public static final String FIXED_STORE_PATH = "store/level12/fixed";

    // 文件大小配置
    public static final int COMMITLOG_FILE_SIZE = 1024 * 1024 * 1024; // 1GB
    public static final int CONSUMEQUEUE_FILE_SIZE = 6000000; // 约 300MB (每条索引 20 字节)

    // ConsumeQueue 索引大小
    public static final int CQ_STORE_UNIT_SIZE = 20; // 8 + 4 + 8

    // 性能测试配置
    public static final int DEFAULT_MESSAGE_SIZE = 1024; // 1KB
    public static final int DEFAULT_TEST_COUNT = 10000;
}
