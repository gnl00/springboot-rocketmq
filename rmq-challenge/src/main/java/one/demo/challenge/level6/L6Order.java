package one.demo.challenge.level6;

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
public class L6Order {
    private String orderId;
    private String userId;
    private String productId;
    private Integer quantity;
    private BigDecimal amount;
    private L6OrderState state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public L6Order(String orderId, String userId, String productId, Integer quantity, BigDecimal amount) {
        this.orderId = orderId;
        this.userId = userId;
        this.productId = productId;
        this.quantity = quantity;
        this.amount = amount;
        this.state = L6OrderState.PENDING;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }
}
