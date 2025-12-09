package one.demo.challenge.level11;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * Level 11 消费者（Buggy 版本）
 *
 * Bug 分析：
 * 1. 消费消息时没有记录轨迹信息
 * 2. 没有记录消费开始和结束时间
 * 3. 没有计算处理耗时
 * 4. 没有记录错误信息
 * 5. 没有记录重试次数
 * 6. 无法追踪消息的完整生命周期
 *
 * 问题现象：
 * 1. 消息处理慢，但不知道慢在哪里
 * 2. 消息失败，但不知道失败原因
 * 3. 无法统计端到端延迟
 * 4. 无法分析性能瓶颈
 * 5. 排查问题困难
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = Level11Constants.ORDER_TOPIC,
        consumerGroup = Level11Constants.CONSUMER_GROUP,
        endpoints = Level11Constants.ENDPOINTS,
        tag = "*",
        consumptionThreadCount = 4
)
public class Level11ConsumerBuggy implements RocketMQListener {

    @Autowired
    private Level11TraceService traceService;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules();

    private final Random random = new Random();

    @Override
    public ConsumeResult consume(MessageView messageView) {
        String body = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();

        try {
            Level11OrderMessage message = objectMapper.readValue(body, Level11OrderMessage.class);

            // Bug 1: 消费开始时没有记录轨迹
            log.info("📥 [Buggy] 收到订单消息 - OrderId: {}, TraceId: {}, Mode: {}, Thread: {}",
                    message.getOrderId(), message.getTraceId(), message.getMode(),
                    Thread.currentThread().getName());

            // Bug 2: 处理消息时没有记录性能指标
            processOrder(message);

            // Bug 3: 处理成功后没有记录轨迹
            log.info("✅ [Buggy] 订单处理成功 - OrderId: {}, TraceId: {}",
                    message.getOrderId(), message.getTraceId());

            return ConsumeResult.SUCCESS;

        } catch (Exception e) {
            // Bug 4: 处理失败时没有记录错误信息和轨迹
            log.error("❌ [Buggy] 订单处理失败", e);
            return ConsumeResult.FAILURE;
        }
    }

    /**
     * 处理订单（Buggy 版本）
     * Bug: 没有记录处理耗时和性能指标
     */
    private void processOrder(Level11OrderMessage message) throws Exception {
        Level11ProcessingMode mode = message.getMode();

        switch (mode) {
            case FAST -> processFast(message);
            case NORMAL -> processNormal(message);
            case SLOW -> processSlow(message);
            case VERY_SLOW -> processVerySlow(message);
            case RANDOM_FAIL -> processRandomFail(message);
        }
    }

    /**
     * 快速处理
     */
    private void processFast(Level11OrderMessage message) throws InterruptedException {
        Thread.sleep(50);
        log.debug("⚡ [Buggy] 快速处理完成 - OrderId: {}", message.getOrderId());
    }

    /**
     * 正常处理
     */
    private void processNormal(Level11OrderMessage message) throws InterruptedException {
        Thread.sleep(200);
        log.debug("✅ [Buggy] 正常处理完成 - OrderId: {}", message.getOrderId());
    }

    /**
     * 慢处理
     */
    private void processSlow(Level11OrderMessage message) throws InterruptedException {
        Thread.sleep(1000);
        log.warn("🐌 [Buggy] 慢处理完成 - OrderId: {}, 耗时: 1000ms", message.getOrderId());
    }

    /**
     * 超慢处理
     */
    private void processVerySlow(Level11OrderMessage message) throws InterruptedException {
        Thread.sleep(3000);
        log.warn("🐢 [Buggy] 超慢处理完成 - OrderId: {}, 耗时: 3000ms", message.getOrderId());
    }

    /**
     * 随机失败
     */
    private void processRandomFail(Level11OrderMessage message) throws Exception {
        Thread.sleep(100);

        if (random.nextBoolean()) {
            throw new RuntimeException("模拟随机业务异常");
        }

        log.debug("✅ [Buggy] 随机处理成功 - OrderId: {}", message.getOrderId());
    }
}
