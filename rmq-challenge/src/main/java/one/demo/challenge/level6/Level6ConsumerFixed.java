package one.demo.challenge.level6;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;

/**
 * Level 6 消费者（Fixed 版本）
 *
 * 改进点：
 * 1. 检查订单是否存在
 * 2. 幂等性保证（避免重复消费）
 * 3. 异常处理优化
 */
@Slf4j
// @Component
public class Level6ConsumerFixed {

    private static final String ENDPOINTS = "localhost:8081";
    private static final String TOPIC = "order-transaction-topic";
    private static final String CONSUMER_GROUP = "order-consumer-group-fixed";

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private PointsService pointsService;

    @Autowired
    private L6OrderService l6OrderService;

    private PushConsumer pushConsumer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() throws ClientException {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(ENDPOINTS)
                .setRequestTimeout(Duration.ofSeconds(3))
                .build();

        FilterExpression filterExpression = new FilterExpression("*", FilterExpressionType.TAG);

        this.pushConsumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(CONSUMER_GROUP)
                .setSubscriptionExpressions(Collections.singletonMap(TOPIC, filterExpression))
                .setMessageListener(messageView -> {
                    try {
                        String body = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();
                        L6OrderEvent event = objectMapper.readValue(body, L6OrderEvent.class);

                        log.info("📥 收到订单事件 - OrderId: {}, EventType: {}",
                                event.getOrderId(), event.getEventType());

                        // 处理订单事件
                        processOrderEvent(event);

                        return ConsumeResult.SUCCESS;

                    } catch (Exception e) {
                        log.error("❌ 消息处理失败", e);
                        return ConsumeResult.FAILURE;
                    }
                })
                .build();

        log.info("✅ Level 6 Consumer (Fixed) 初始化完成");
    }

    @PreDestroy
    public void destroy() {
        if (pushConsumer != null) {
            try {
                pushConsumer.close();
            } catch (Exception e) {
                log.error("关闭 Consumer 失败", e);
            }
        }
    }

    /**
     * 处理订单事件
     * 改进：检查订单是否存在，保证数据一致性
     */
    private void processOrderEvent(L6OrderEvent event) {
        if ("ORDER_CREATED".equals(event.getEventType())) {
            // 改进1: 检查订单是否存在
            L6Order l6Order = l6OrderService.getOrder(event.getOrderId());
            if (l6Order == null) {
                log.error("❌ 订单不存在，拒绝处理消息 - OrderId: {}", event.getOrderId());
                throw new RuntimeException("订单不存在，数据不一致");
            }

            // 改进2: 检查订单状态，避免重复处理
            if (l6Order.getState() == L6OrderState.CONFIRMED) {
                log.warn("⚠️ 订单已处理，跳过重复消息 - OrderId: {}", event.getOrderId());
                return;
            }

            // 扣减库存
            boolean success = inventoryService.deductInventory(event.getProductId(), event.getQuantity());
            if (!success) {
                log.error("❌ 库存扣减失败 - OrderId: {}", event.getOrderId());
                // 改进3: 库存扣减失败，取消订单
                l6OrderService.cancelOrder(event.getOrderId());
                throw new RuntimeException("库存不足，订单已取消");
            }

            // 增加积分
            pointsService.addPoints(event.getUserId(), event.getAmount());

            // 确认订单
            l6OrderService.confirmOrder(event.getOrderId());

            log.info("✅ 订单事件处理完成 - OrderId: {}", event.getOrderId());
        }
    }
}
