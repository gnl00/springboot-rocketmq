package one.demo.challenge.level7;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RocketMQMessageListener(
        topic = "order-cancel-topic",
        consumerGroup = "order-cancel-consumer-Fixed",
        endpoints = "localhost:8081",
        tag = "*"
)
public class Level7ConsumerFixed implements RocketMQListener {

    @Autowired
    private OrderService orderService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ConsumeResult consume(MessageView messageView) {
        try {
            String body = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();
            OrderCancelEvent event = objectMapper.readValue(body, OrderCancelEvent.class);

            log.info("📥 [Fixed消费者] 收到订单取消消息 - OrderId: {}, Reason: {}",
                    event.getOrderId(), event.getReason());

            Order order = orderService.getOrder(event.getOrderId());
            if (order == null) {
                log.warn("⚠️ [Fixed消费者] 订单不存在 - OrderId: {}", event.getOrderId());
                return ConsumeResult.SUCCESS;
            }
            
            // 检查订单状态
            boolean success = orderService.cancelOrder(event.getOrderId());
            if (success) {
                log.info("✅ [Fixed消费者] 订单已取消 - OrderId: {}", event.getOrderId());
            } else {
                log.warn("⚠️ [Fixed消费者] 订单取消失败 - OrderId: {}", event.getOrderId());
            }

            return ConsumeResult.SUCCESS;

        } catch (Exception e) {
            log.error("❌ [Fixed消费者] 消息处理失败", e);
            return ConsumeResult.FAILURE;
        }
    }
}
