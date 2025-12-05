package one.demo.challenge.level8.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import one.demo.challenge.level8.Level8Constants;
import one.demo.challenge.level8.Level8ConsumerStatsService;
import one.demo.challenge.level8.Level8OrderMessage;
import one.demo.challenge.level8.Level8OrderType;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Level 8 中针对订单类型做 Tag 路由的消费者（Buggy 版本）。
 * Bug：所有消费者都订阅了 "*"，导致收到所有类型的消息。
 */
@Slf4j
// @Component
public class Level8TagConsumersBuggy {

    private final Level8ConsumerStatsService statsService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<PushConsumer> consumers = new ArrayList<>();

    private ClientServiceProvider provider;
    private ClientConfiguration configuration;

    public Level8TagConsumersBuggy(Level8ConsumerStatsService statsService) {
        this.statsService = statsService;
    }

    @PostConstruct
    public void init() throws ClientException {
        provider = ClientServiceProvider.loadService();
        configuration = ClientConfiguration.newBuilder()
                .setEndpoints(Level8Constants.ENDPOINTS)
                .setRequestTimeout(Duration.ofSeconds(3))
                .build();

        register("normal-order-consumer", Level8OrderType.NORMAL);
        register("seckill-order-consumer", Level8OrderType.SECKILL);
        register("presale-order-consumer", Level8OrderType.PRESALE);
        register("vip-order-consumer", Level8OrderType.VIP);

        log.info("✅ Level8 Tag Consumers (Buggy) 初始化完成，全部订阅 *");
    }

    private void register(String consumerName, Level8OrderType expectedType) throws ClientException {
        FilterExpression filterExpression = new FilterExpression("*", FilterExpressionType.TAG);
        PushConsumer consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup("level8-" + consumerName)
                .setSubscriptionExpressions(Collections.singletonMap(Level8Constants.ORDER_TOPIC, filterExpression))
                .setMessageListener(messageView -> consume(consumerName, expectedType, messageView))
                .build();

        consumers.add(consumer);
    }

    private ConsumeResult consume(String consumerName,
                                  Level8OrderType expectedType,
                                  MessageView messageView) {
        try {
            String body = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();
            Level8OrderMessage orderMessage = objectMapper.readValue(body, Level8OrderMessage.class);

            // Bug：没有根据 Tag 过滤，所有消费者都会处理所有消息
            statsService.record(
                    consumerName,
                    orderMessage,
                    String.format("期望处理: %s, 实际收到: %s", expectedType, orderMessage.getOrderType()));

            log.info("📥 [{}] 收到订单 - OrderId={}, Type={}",
                    consumerName, orderMessage.getOrderId(), orderMessage.getOrderType());

            return ConsumeResult.SUCCESS;
        } catch (Exception e) {
            log.error("❌ [{}] 消息处理失败", consumerName, e);
            return ConsumeResult.FAILURE;
        }
    }

    @PreDestroy
    public void destroy() {
        consumers.forEach(consumer -> {
            try {
                consumer.close();
            } catch (Exception e) {
                log.warn("关闭消费者失败", e);
            }
        });
    }
}
