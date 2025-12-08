package one.demo.challenge.level10;

/**
 * Level 10 订单类型
 */
public enum Level10OrderType {
    NORMAL("普通订单"),
    BULK("批量订单"),
    URGENT("紧急订单");

    private final String description;

    Level10OrderType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
