package one.demo.challenge.level6;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;

/**
 * Level 6 消费者（Buggy 版本）
 *
 * 消费订单事件，执行下游业务逻辑：
 * 1. 扣减库存
 * 2. 增加积分
 */
@Slf4j
@Component
@RocketMQMessageListener(topic = "order-transaction-topic", tag = "*", consumerGroup = "order-consumer-group-buggy", endpoints = "localhost:8080")
public class Level6ConsumerBuggy implements RocketMQListener {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private PointsService pointsService;

    @Autowired
    private L6OrderService l6OrderService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 处理订单事件
     * Bug: 没有检查订单是否存在，直接处理
     */
    private void processOrderEvent(L6OrderEvent event) {
        if ("ORDER_CREATED".equals(event.getEventType())) {
            // Bug: 没有检查订单是否真的存在
            L6Order l6Order = l6OrderService.getOrder(event.getOrderId());
            if (l6Order == null) {
                log.warn("⚠️ 订单不存在，但仍然处理消息 - OrderId: {}", event.getOrderId());
                // 继续处理，导致数据不一致
            }

            // 扣减库存
            boolean success = inventoryService.deductInventory(event.getProductId(), event.getQuantity());
            if (!success) {
                log.error("❌ 库存扣减失败 - OrderId: {}", event.getOrderId());
                // Bug: 库存扣减失败，但积分仍然增加
            }

            // 增加积分
            pointsService.addPoints(event.getUserId(), event.getAmount());

            log.info("✅ 订单事件处理完成 - OrderId: {}", event.getOrderId());
        }
    }

    @Override
    public ConsumeResult consume(MessageView messageView) {
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
    }
}
