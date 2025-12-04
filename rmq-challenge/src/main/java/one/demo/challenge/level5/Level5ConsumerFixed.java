package one.demo.challenge.level5;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Level 5 消费者 - Fixed 版本
 *
 * 解决方案：
 * 1. 生产者：使用 setMessageGroup(orderId) 按订单分区，保证同一订单的消息在同一 MessageGroup
 * 2. 消费者：consumptionThreadCount = 1，保证单线程顺序处理
 *
 * 关键理解：
 * - RocketMQ v5 的 FIFO 保证是"投递有序"，不是"处理完成有序"
 * - 如果 consumptionThreadCount > 1，多线程并发处理会导致完成顺序乱序
 * - 必须单线程才能保证处理完成也是有序的
 *
 * 性能提升方式：
 * - 生产者按 orderId 分区：不同订单可以并发处理
 * - 消费者水平扩展：部署多个实例，总并发度 = 实例数
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "order-status-topic",
        tag = "*",
        consumerGroup = "fifoGroup",
        endpoints = "localhost:8080"
        // ,consumptionThreadCount = 1  // ✅ 必须为 1，保证同一 MessageGroup 的消息顺序处理
)
public class Level5ConsumerFixed implements RocketMQListener {

    @Autowired
    private OrderStatusManager orderStatusManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 消费速率统计
    private static final AtomicLong consumedCount = new AtomicLong(0);
    private static final AtomicLong startTime = new AtomicLong(System.currentTimeMillis());

    @Override
    public ConsumeResult consume(MessageView messageView) {
        long currentCount = consumedCount.incrementAndGet();

        try {
            String messageBody = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();
            OrderStatusEvent event = objectMapper.readValue(messageBody, OrderStatusEvent.class);

            log.info("📥 收到订单状态变更消息 - {}, Thread: {}",
                    event, Thread.currentThread().getName());

            // 模拟处理耗时
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

            // 计算消费速率（每 10 条统计一次）
            if (currentCount % 10 == 0) {
                long elapsed = System.currentTimeMillis() - startTime.get();
                double rate = currentCount * 1000.0 / elapsed;
                log.info(String.format("📊 消费统计（Fixed 版本）- 已消费: %d 条, 耗时: %d ms, 速率: %.2f msg/s, 线程: %s",
                        currentCount, elapsed, rate, Thread.currentThread().getName()));
            }

            return ConsumeResult.SUCCESS;
        } catch (Exception e) {
            log.error("消费消息失败 - MessageId: {}", messageView.getMessageId(), e);
            return ConsumeResult.FAILURE;
        }
    }
}
