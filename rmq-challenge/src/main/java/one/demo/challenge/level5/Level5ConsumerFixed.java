package one.demo.challenge.level5;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Level 5 消费者 - Fixed 版本
 *
 * Bug：使用并发消费模式，无法保证消息顺序
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "order-status-topic",
        tag = "*",
        consumerGroup = "order-status-consumer-fixed",
        endpoints = "localhost:8080",
        consumptionThreadCount = 1  // 串行消费
)
public class Level5ConsumerFixed implements RocketMQListener {

    @Autowired
    private OrderStatusManager orderStatusManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ConsumeResult consume(MessageView messageView) {
        try {
            String messageBody = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();
            OrderStatusEvent event = objectMapper.readValue(messageBody, OrderStatusEvent.class);

            log.info("📥 收到订单状态变更消息 - {}, Thread: {}",
                    event, Thread.currentThread().getName());

            // 模拟处理耗时，加剧乱序问题
            TimeUnit.MILLISECONDS.sleep(50 + (int) (Math.random() * 100));

            // 更新订单状态
            boolean success = orderStatusManager.updateStatus(
                    event.getOrderId(),
                    event.getStatus(),
                    event.getSequenceNo()
            );

            if (!success) {
                log.warn("⚠️ 订单状态更新失败 - {}", event);
            }

            return ConsumeResult.SUCCESS;

        } catch (Exception e) {
            log.error("消费消息失败 - MessageId: {}", messageView.getMessageId(), e);
            return ConsumeResult.FAILURE;
        }
    }
}
