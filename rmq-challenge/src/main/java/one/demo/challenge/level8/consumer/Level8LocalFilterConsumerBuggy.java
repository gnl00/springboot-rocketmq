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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;

/**
 * Bug 场景 4：消费者端过滤。所有消息都被推送到客户端，再由 Java 代码决定是否处理。
 * 这种方式会大幅增加延迟，并且浪费 CPU。
 */
@Slf4j
// @Component
public class Level8LocalFilterConsumerBuggy {

    private final Level8ConsumerStatsService statsService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private PushConsumer consumer;

    public Level8LocalFilterConsumerBuggy(Level8ConsumerStatsService statsService) {
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

            FilterExpression subAll = new FilterExpression("*", FilterExpressionType.TAG);
            this.consumer = provider.newPushConsumerBuilder()
                    .setClientConfiguration(configuration)
                    .setConsumerGroup("level8-local-filter-consumer")
                    .setSubscriptionExpressions(Collections.singletonMap(Level8Constants.ORDER_TOPIC, subAll))
                    .setMessageListener(this::handleMessage)
                    .build();

            log.info("✅ [Buggy] LocalFilterConsumer 初始化完成，所有消息都拉到客户端过滤。");
        } catch (ClientException e) {
            log.error("❌ 初始化 Level8 LocalFilterConsumer 失败", e);
        }
    }

    private ConsumeResult handleMessage(MessageView messageView) {
        long start = System.currentTimeMillis();
        try {
            String body = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();
            Level8OrderMessage orderMessage = objectMapper.readValue(body, Level8OrderMessage.class);

            boolean shouldProcess = heavyFilter(orderMessage);
            statsService.record("local-filter-consumer", orderMessage,
                    shouldProcess ? "执行本地过滤后处理" : "执行本地过滤后丢弃");

            if (shouldProcess) {
                log.info("✅ [local-filter-consumer] 处理订单 - OrderId={}, Amount={}, 耗时={}ms",
                        orderMessage.getOrderId(), orderMessage.getAmount(),
                        System.currentTimeMillis() - start);
            } else {
                log.info("⏭ [local-filter-consumer] 丢弃订单 - OrderId={}, 耗时={}ms",
                        orderMessage.getOrderId(), System.currentTimeMillis() - start);
            }
            return ConsumeResult.SUCCESS;
        } catch (Exception e) {
            log.error("❌ local-filter-consumer 消息处理失败", e);
            return ConsumeResult.FAILURE;
        }
    }

    /**
     * 模拟复杂过滤逻辑，加入 Thread.sleep。
     */
    private boolean heavyFilter(Level8OrderMessage message) {
        try {
            Thread.sleep(2); // 模拟外部服务调用
        } catch (InterruptedException ignored) {
        }
        BigDecimal threshold = BigDecimal.valueOf(150);
        return "VIP".equalsIgnoreCase(message.getUserLevel())
                && message.getAmount() != null
                && message.getAmount().compareTo(threshold) > 0;
    }

    @PreDestroy
    public void destroy() {
        if (consumer != null) {
            try {
                consumer.close();
            } catch (Exception e) {
                log.warn("关闭 local-filter-consumer 失败", e);
            }
        }
    }
}
