package one.demo.challenge.level8;

/**
 * 公共常量，集中维护 Level 8 挑战中 Producer 与 Consumer 需要共享的配置。
 */
public final class Level8Constants {

    /**
     * MQ 访问地址，保持与其它 Level 挑战一致，方便本地 Docker 环境复用。
     */
    public static final String ENDPOINTS = "localhost:8081";

    /**
     * Level 8 统一使用的 Topic，后续 Fixed 版本可以复用此常量。
     */
    public static final String ORDER_TOPIC = "level8-order-topic";

    private Level8Constants() {
    }
}
