package one.demo.challenge.level8.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import one.demo.challenge.level8.Level8Constants;
import one.demo.challenge.level8.Level8ConsumerStatsService;
import one.demo.challenge.level8.Level8OrderMessage;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;

/**
 * 秒杀订单消费者（Buggy）：订阅 Tag 写成了 seckill_order（下划线），
 * 而 Producer 使用的是 seckill-order（连字符），导致无法消费任何消息。
 */
@Slf4j
// @Component
public class Level8StrictSeckillConsumerBuggy {

    private final Level8ConsumerStatsService statsService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private PushConsumer consumer;

    public Level8StrictSeckillConsumerBuggy(Level8ConsumerStatsService statsService) {
        this.statsService = statsService;
    }

    @PostConstruct
    public void init() {
        try {
            ClientServiceProvider provider = ClientServiceProvider.loadService();
            ClientConfiguration configuration = ClientConfiguration.newBuilder()
                    .setEndpoints(Level8Constants.ENDPOINTS)
                    .setRequestTimeout(Duration.ofSeconds(3))
                    .build();

            FilterExpression wrongExpression = new FilterExpression("seckill_order", FilterExpressionType.TAG);
            this.consumer = provider.newPushConsumerBuilder()
                    .setClientConfiguration(configuration)
                    .setConsumerGroup("level8-strict-seckill-consumer")
                    .setSubscriptionExpressions(Collections.singletonMap(Level8Constants.ORDER_TOPIC, wrongExpression))
                    .setMessageListener(this::onMessage)
                    .build();

            log.info("✅ [Buggy] 秒杀消费者已初始化，但 Tag 写成 seckill_order（下划线）。");
        } catch (ClientException e) {
            log.error("❌ 初始化秒杀消费者失败", e);
        }
    }

    private ConsumeResult onMessage(MessageView messageView) {
        try {
            String body = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();
            Level8OrderMessage orderMessage = objectMapper.readValue(body, Level8OrderMessage.class);
            statsService.record("strict-seckill-consumer", orderMessage, "严格 Tag 订阅");
            log.info("📥 [strict-seckill-consumer] 收到订单 - {}", orderMessage.getOrderId());
            return ConsumeResult.SUCCESS;
        } catch (Exception e) {
            log.error("❌ strict-seckill-consumer 消息处理失败", e);
            return ConsumeResult.FAILURE;
        }
    }

    @PreDestroy
    public void destroy() {
        if (consumer != null) {
            try {
                consumer.close();
            } catch (Exception e) {
                log.warn("关闭 strict-seckill-consumer 失败", e);
            }
        }
    }
}
