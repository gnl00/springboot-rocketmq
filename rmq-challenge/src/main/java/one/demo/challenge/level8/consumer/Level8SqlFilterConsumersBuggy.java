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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQL 过滤消费者（Buggy）。
 * - 北京消费者：SQL 表达式缺少引号，导致解析失败；
 * - 金额消费者：把 amount 当作字符串比较，结果失真。
 */
@Slf4j
// @Component
public class Level8SqlFilterConsumersBuggy {

    private final Level8ConsumerStatsService statsService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<PushConsumer> consumers = new ArrayList<>();

    public Level8SqlFilterConsumersBuggy(Level8ConsumerStatsService statsService) {
        this.statsService = statsService;
    }

    @PostConstruct
    public void init() {
        createBeijingConsumer();
        createHighAmountConsumer();
    }

    private void createBeijingConsumer() {
        try {
            ClientServiceProvider provider = ClientServiceProvider.loadService();
            ClientConfiguration configuration = ClientConfiguration.newBuilder()
                    .setEndpoints(Level8Constants.ENDPOINTS)
                    .setRequestTimeout(Duration.ofSeconds(3))
                    .build();

            // Bug：region = beijing，少了引号，RocketMQ 会判定 SQL 不合法
            FilterExpression expression = new FilterExpression(
                    "region = beijing AND amount > 100",
                    FilterExpressionType.SQL92);

            PushConsumer consumer = provider.newPushConsumerBuilder()
                    .setClientConfiguration(configuration)
                    .setConsumerGroup("level8-beijing-consumer")
                    .setSubscriptionExpressions(Collections.singletonMap(Level8Constants.ORDER_TOPIC, expression))
                    .setMessageListener(messageView -> consume("beijing-consumer", messageView))
                    .build();

            consumers.add(consumer);
            log.info("✅ [Buggy] 北京地区消费者初始化完成（SQL 缺少引号，可能无法消费）。");
        } catch (ClientException e) {
            log.error("❌ 初始化北京地区消费者失败：{}", e.getMessage());
        }
    }

    private void createHighAmountConsumer() {
        try {
            ClientServiceProvider provider = ClientServiceProvider.loadService();
            ClientConfiguration configuration = ClientConfiguration.newBuilder()
                    .setEndpoints(Level8Constants.ENDPOINTS)
                    .setRequestTimeout(Duration.ofSeconds(3))
                    .build();

            // Bug：把 amount 当作字符串比较，会出现 80 > 100 的错觉
            FilterExpression expression = new FilterExpression(
                    "amount > '100'",
                    FilterExpressionType.SQL92);

            PushConsumer consumer = provider.newPushConsumerBuilder()
                    .setClientConfiguration(configuration)
                    .setConsumerGroup("level8-high-amount-consumer")
                    .setSubscriptionExpressions(Collections.singletonMap(Level8Constants.ORDER_TOPIC, expression))
                    .setMessageListener(messageView -> consume("high-amount-consumer", messageView))
                    .build();

            consumers.add(consumer);
            log.info("✅ [Buggy] 高金额消费者初始化完成（比较逻辑错误）。");
        } catch (ClientException e) {
            log.error("❌ 初始化高金额消费者失败：{}", e.getMessage());
        }
    }

    private ConsumeResult consume(String consumerName, MessageView messageView) {
        try {
            String body = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();
            Level8OrderMessage message = objectMapper.readValue(body, Level8OrderMessage.class);
            statsService.record(consumerName, message, "SQL 过滤");
            log.info("📥 [{}] 收到订单 - OrderId={}, Region={}, Amount={}",
                    consumerName, message.getOrderId(), message.getRegion(), message.getAmount());
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
                log.warn("关闭 SQL 过滤消费者失败", e);
            }
        });
    }
}
