package one.demo.challenge.level11;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Level 11 订单消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Level11OrderMessage {
    private String traceId;      // 追踪 ID
    private String orderId;
    private String userId;
    private String productId;
    private Integer quantity;
    private BigDecimal amount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    // 模拟不同的处理场景
    private Level11ProcessingMode mode;

    public Level11OrderMessage(String traceId, String orderId, String userId,
                              String productId, Integer quantity, BigDecimal amount,
                              Level11ProcessingMode mode) {
        this.traceId = traceId;
        this.orderId = orderId;
        this.userId = userId;
        this.productId = productId;
        this.quantity = quantity;
        this.amount = amount;
        this.mode = mode;
        this.createTime = LocalDateTime.now();
    }
}
