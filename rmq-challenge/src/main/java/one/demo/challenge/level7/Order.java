package one.demo.challenge.level7;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Order {
    private String orderId;
    private String userId;
    private String productId;
    private Integer quantity;
    private BigDecimal amount;
    private OrderStatus status;
    private LocalDateTime createTime;
    private LocalDateTime expireTime;  // 过期时间
    private LocalDateTime updateTime;

    public Order(String orderId, String userId, String productId, Integer quantity, BigDecimal amount) {
        this.orderId = orderId;
        this.userId = userId;
        this.productId = productId;
        this.quantity = quantity;
        this.amount = amount;
        this.status = OrderStatus.PENDING;
        this.createTime = LocalDateTime.now();
        this.expireTime = LocalDateTime.now().plusMinutes(30); // 默认30分钟后过期
        this.updateTime = LocalDateTime.now();
    }
}
