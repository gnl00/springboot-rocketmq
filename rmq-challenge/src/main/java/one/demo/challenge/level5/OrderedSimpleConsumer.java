package one.demo.challenge.level5;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
// @Component
public class OrderedSimpleConsumer {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SimpleConsumer consumer;

    public OrderedSimpleConsumer(final OrderStatusManager orderStatusManager) throws ClientException {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration clientConfig = ClientConfiguration.newBuilder()
                .setEndpoints("localhost:8080")
                .build();
        consumer = provider.newSimpleConsumerBuilder()
                .setConsumerGroup("fifoGroup")
                .setAwaitDuration(Duration.ofSeconds(20))
                .setSubscriptionExpressions(Map.of("order-status-topic", FilterExpression.SUB_ALL))
                .setClientConfiguration(clientConfig)
                .build();
        log.info("OrderedSimpleConsumer 初始化完成");
        new Thread(() -> {
            while (true) {
                List<MessageView> messageViewList = null;
                try {
                    messageViewList = consumer.receive(32, Duration.ofSeconds(15));
                } catch (ClientException e) {
                    log.error("暂无消息", e);
                }
                if (messageViewList == null) continue;
                messageViewList.forEach(messageView -> {
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

                        consumer.ack(messageView);
                    } catch (Exception e) {
                        log.error("消费消息失败 - MessageId: {}", messageView.getMessageId(), e);
                    }
                });
            }
        }).start();
    }

    @PreDestroy
    public void init() throws ClientException {
        if (consumer != null) {
            try {
                consumer.close();
                log.info("OrderedSimpleConsumer closed");
            } catch (IOException e) {
                log.error("OrderedSimpleConsumer close failed, e=", e);
            }
        }
    }
}
