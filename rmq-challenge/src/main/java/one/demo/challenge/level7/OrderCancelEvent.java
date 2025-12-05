package one.demo.challenge.level7;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单取消事件（延时消息）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCancelEvent {
    private String orderId;
    private String reason;  // 取消原因：TIMEOUT（超时）、USER_CANCEL（用户取消）
}
