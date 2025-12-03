package one.demo.challenge.level5;

/**
 * 订单状态枚举
 */
public enum OrderStatus {
    CREATED("订单创建"),
    PAID("已支付"),
    SHIPPED("已发货"),
    COMPLETED("已完成"),
    CANCELLED("已取消");

    private final String description;

    OrderStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
