package one.demo.challenge.level6;

/**
 * 订单状态
 */
public enum L6OrderState {
    PENDING("待处理"),
    CONFIRMED("已确认"),
    CANCELLED("已取消");

    private final String description;

    L6OrderState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
