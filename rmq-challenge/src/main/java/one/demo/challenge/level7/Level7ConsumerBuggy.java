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

/**
 * Level 7 消费者（Buggy 版本）
 *
 * Bug 分析：
 * 1. 没有检查订单状态，直接取消订单
 * 2. 没有幂等性保证，重复消费会导致库存多次恢复
 * 3. 没有处理订单已支付的情况
 */
@Slf4j
// @Component
@RocketMQMessageListener(
        topic = "order-cancel-topic",
        consumerGroup = "order-cancel-consumer-buggy",
        endpoints = "localhost:8081",
        tag = "*"
)
public class Level7ConsumerBuggy implements RocketMQListener {

    @Autowired
    private OrderService orderService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ConsumeResult consume(MessageView messageView) {
        try {
            String body = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();
            OrderCancelEvent event = objectMapper.readValue(body, OrderCancelEvent.class);

            log.info("📥 [Buggy消费者] 收到订单取消消息 - OrderId: {}, Reason: {}",
                    event.getOrderId(), event.getReason());

            // Bug 1: 没有检查订单状态，直接取消
            // 如果订单已支付，这里会错误地取消订单
            Order order = orderService.getOrder(event.getOrderId());
            if (order == null) {
                log.warn("⚠️ [Buggy消费者] 订单不存在 - OrderId: {}", event.getOrderId());
                return ConsumeResult.SUCCESS;
            }

            // Bug 2: 没有检查订单状态
            // 即使订单已支付，也会被取消
            boolean success = orderService.cancelOrder(event.getOrderId());
            if (success) {
                log.info("✅ [Buggy消费者] 订单已取消 - OrderId: {}", event.getOrderId());
            } else {
                log.warn("⚠️ [Buggy消费者] 订单取消失败 - OrderId: {}", event.getOrderId());
            }

            return ConsumeResult.SUCCESS;

        } catch (Exception e) {
            log.error("❌ [Buggy消费者] 消息处理失败", e);
            return ConsumeResult.FAILURE;
        }
    }
}
