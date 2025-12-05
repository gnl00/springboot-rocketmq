package one.demo.challenge.level8;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * MQ 中承载的订单消息结构，方便序列化及统计。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Level8OrderMessage {

    private String orderId;
    private Level8OrderType orderType;
    private String userId;
    private String region;
    private BigDecimal amount;
    private String userLevel;
    private Long createdAt;

    public static Level8OrderMessage of(Level8OrderType orderType,
                                        String orderId,
                                        BigDecimal amount,
                                        String region,
                                        String userId,
                                        String userLevel) {
        return Level8OrderMessage.builder()
                .orderId(orderId)
                .orderType(orderType)
                .amount(amount)
                .region(region)
                .userId(userId)
                .userLevel(userLevel)
                .createdAt(System.currentTimeMillis())
                .build();
    }
}
