package one.demo.challenge.level10;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Level 10 订单实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Level10Order {
    private String orderId;
    private String userId;
    private Level10OrderType orderType;
    private BigDecimal amount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    private String status;

    public Level10Order(String orderId, String userId, Level10OrderType orderType, BigDecimal amount) {
        this.orderId = orderId;
        this.userId = userId;
        this.orderType = orderType;
        this.amount = amount;
        this.createTime = LocalDateTime.now();
        this.status = "PENDING";
    }
}
