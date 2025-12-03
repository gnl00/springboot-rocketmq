package one.demo.challenge.level4;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Level 4 挑战：消息积压问题
 *
 * 问题场景：
 * 生产者每秒发送 100 条消息，但消费者每条消息处理需要 500ms，
 * 导致消息在 Broker 中堆积，积压越来越严重。
 *
 * 问题现象：
 * 1. 消息堆积数量持续增长（从 0 → 1000 → 10000 → ...）
 * 2. 消息消费延迟越来越高（从秒级 → 分钟级 → 小时级）
 * 3. 消费者 CPU 使用率不高，但就是处理不过来
 * 4. 业务告警：订单、支付、通知等消息延迟
 *
 * 任务：
 * 1. 找出导致消息积压的根本原因
 * 2. 分析消费者的性能瓶颈
 * 3. 提出并实现优化方案
 *
 * 提示：
 * - 生产速度 vs 消费速度
 * - 单线程 vs 多线程
 * - 同步阻塞 vs 异步非阻塞
 * - 消费者并发度配置
 */
@Slf4j
// @Component
@RocketMQMessageListener(
        topic = "order-notification",
        tag = "*",
        consumerGroup = "notification-consumer-buggy",
        endpoints = "localhost:8080"
        // Bug 1: 没有配置消费并发度，默认可能只有 1 个线程
        // Bug 2: 没有配置批量消费
)
public class Level4ConsumerBuggy implements RocketMQListener {

    private static final AtomicLong consumedCount = new AtomicLong(0);
    private static final AtomicLong startTime = new AtomicLong(System.currentTimeMillis());

    @Override
    public ConsumeResult consume(MessageView messageView) {
        long currentCount = consumedCount.incrementAndGet();

        try {
            String messageBody = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();

            // Bug 3: 消费逻辑太慢 - 模拟调用第三方 API、发送邮件、写数据库等耗时操作
            // 每条消息处理需要 500ms
            processSlowOperation(messageBody);

            // 计算消费速率
            if (currentCount % 100 == 0) {
                long elapsed = System.currentTimeMillis() - startTime.get();
                double rate = currentCount * 1000.0 / elapsed;
                log.info(String.format("📊 消费统计 - 已消费: %d 条, 耗时: %d ms, 速率: %.2f msg/s",
                        currentCount, elapsed, rate));
            }

            return ConsumeResult.SUCCESS;

        } catch (Exception e) {
            log.error("消费消息失败 - MessageId: {}", messageView.getMessageId(), e);
            return ConsumeResult.FAILURE;
        }
    }

    /**
     * 模拟慢速操作
     * Bug 4: 同步阻塞操作，每个操作都需要等待完成
     */
    private void processSlowOperation(String message) throws InterruptedException {
        // 模拟数据库查询
        TimeUnit.MILLISECONDS.sleep(100);

        // 模拟调用第三方 API
        TimeUnit.MILLISECONDS.sleep(200);

        // 模拟发送通知（邮件、短信等）
        TimeUnit.MILLISECONDS.sleep(200);

        // 总耗时：500ms
        log.debug("处理消息: {}", message);
    }
}
