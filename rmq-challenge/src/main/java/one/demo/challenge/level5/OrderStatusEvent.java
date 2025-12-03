package one.demo.challenge.level5;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单状态变更事件
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatusEvent {
    private String orderId;
    private OrderStatus status;
    private long timestamp;
    private int sequenceNo;  // 序列号，用于验证顺序

    public OrderStatusEvent(String orderId, OrderStatus status, int sequenceNo) {
        this.orderId = orderId;
        this.status = status;
        this.sequenceNo = sequenceNo;
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return String.format("OrderStatusEvent{orderId='%s', status=%s, seq=%d, time=%d}",
                orderId, status, sequenceNo, timestamp);
    }
}
