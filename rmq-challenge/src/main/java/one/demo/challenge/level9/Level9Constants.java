package one.demo.challenge.level9;

/**
 * Level 9 公共常量。避免硬编码 Topic、Group 等配置，便于后续 fixed/best 版本复用。
 */
public final class Level9Constants {

    public static final String ENDPOINTS = "localhost:8081";

    public static final String ORDER_TOPIC = "level9-order-topic";

    /**
     * Buggy 版本的消费组，会触发无限重试与 DLQ 问题。
     */
    public static final String CONSUMER_GROUP = "level9-order-consumer-buggy";

    private Level9Constants() {
    }
}
