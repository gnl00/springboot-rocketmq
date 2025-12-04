package one.demo.challenge.level6;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 订单事件（用于MQ消息）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class L6OrderEvent {
    private String orderId;
    private String userId;
    private String productId;
    private Integer quantity;
    private BigDecimal amount;
    private String eventType; // ORDER_CREATED, ORDER_CONFIRMED, ORDER_CANCELLED
}
