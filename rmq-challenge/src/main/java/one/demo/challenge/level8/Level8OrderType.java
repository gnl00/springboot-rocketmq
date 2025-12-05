package one.demo.challenge.level8;

import java.util.Arrays;

/**
 * Level 8 涉及的订单类型。为了方便演示 Tag 路由问题，
 * 每种订单类型都维护一个默认的 Tag 名称。
 */
public enum Level8OrderType {
    NORMAL("normal-order", "普通订单"),
    SECKILL("seckill-order", "秒杀订单"),
    PRESALE("presale-order", "预售订单"),
    VIP("vip-order", "VIP 订单");

    private final String defaultTag;
    private final String description;

    Level8OrderType(String defaultTag, String description) {
        this.defaultTag = defaultTag;
        this.description = description;
    }

    public String getDefaultTag() {
        return defaultTag;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 将 HTTP 请求中的 type 参数转换为枚举。如果传入非法值，默认回退到 NORMAL。
     */
    public static Level8OrderType fromRequest(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return NORMAL;
        }
        return Arrays.stream(values())
                .filter(type -> type.name().equalsIgnoreCase(rawType)
                        || type.getDefaultTag().equalsIgnoreCase(rawType))
                .findFirst()
                .orElse(NORMAL);
    }
}
