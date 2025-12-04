package one.demo.challenge.level5;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class OrderedPushConsumer {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final PushConsumer pushConsumer;

    public OrderedPushConsumer(final OrderStatusManager orderStatusManager) throws ClientException {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration clientConfig = ClientConfiguration.newBuilder()
                .setEndpoints("localhost:8080")
                .build();
        pushConsumer = provider.newPushConsumerBuilder()
                .setConsumerGroup("fifoGroup")
                .setSubscriptionExpressions(Map.of("order-status-topic", FilterExpression.SUB_ALL))
                .setClientConfiguration(clientConfig)
                .setMessageListener(messageView -> {
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
                })
                .build();
        log.info("OrderedConsumer 初始化完成");
    }

    @PreDestroy
    public void init() throws ClientException {
        if (pushConsumer != null) {
            try {
                pushConsumer.close();
                log.info("OrderedConsumer closed");
            } catch (IOException e) {
                log.error("consumer close failed, e=", e);
            }
        }
    }
}
