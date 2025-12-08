package one.demo.challenge.level9;

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
 * Level9 消费者（Fixed）:
 * 1. 所有异常都返回 FAILURE，不区分业务异常；
 * 2. 不记录重试次数/退避策略，导致无限重试；
 * 3. 没有任何 DLQ 监控；
 * 4. 直接阻塞线程模拟超时，易导致线程池耗尽。
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = Level9Constants.ORDER_TOPIC,
        consumerGroup = Level9Constants.CONSUMER_GROUP,
        endpoints = Level9Constants.ENDPOINTS,
        tag = "*"
)
public class Level9ConsumerFxied implements RocketMQListener {

    @Autowired
    private Level9OrderService orderService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    @Override
    public ConsumeResult consume(MessageView messageView) {
        String body = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();
        Level9OrderEvent event;
        try {
            event = objectMapper.readValue(body, Level9OrderEvent.class);
        } catch (Exception parseException) {
            log.error("❌ [Level9 Fixed] 消息解析失败，直接返回 FAILURE，Broker 将无限重试: {}", body, parseException);
            return ConsumeResult.FAILURE;
        }

        String orderId = event.getOrderId();
        orderService.incrementAttempt(orderId);
        orderService.markProcessing(orderId);

        try {
            switch (event.getMode()) {
                case NORMAL -> handleNormal(orderId);
                case BUSINESS_ERROR -> handleBusinessError(event);
                case SYSTEM_TIMEOUT -> simulateTimeout(event);
                case RANDOM_FAILURE -> randomFailure(event);
            }
            orderService.markSuccess(orderId);
            log.info("✅ [Level9 Fixed] 订单处理成功 - OrderId={}, Mode={}", orderId, event.getMode());
            return ConsumeResult.SUCCESS;
        } catch (MQServiceException e) {
            log.warn("[Level9 Fixed] 处理完成，服务异常，- OrderId={}, Mode={}",
                    orderId, event.getMode(), e);
            return ConsumeResult.SUCCESS;
        }catch (Exception ex) {
            orderService.markFailed(orderId, ex.getMessage());
            log.error("❌ [Level9 Fixed] 处理失败，将返回 FAILURE 触发重试 - OrderId={}, Mode={}",
                    orderId, event.getMode(), ex);
            return ConsumeResult.FAILURE;
        }
    }

    private void handleNormal(String orderId) {
        log.info("🛠 [Level9 Fixed] 正常处理订单 {}", orderId);
    }

    private void handleBusinessError(Level9OrderEvent event) {
        if (event.getAmount() != null && event.getAmount().signum() < 0) {
            throw new MQServiceException("金额不能为负数（业务异常）");
        }
        throw new MQServiceException("模拟业务校验失败");
    }

    private void simulateTimeout(Level9OrderEvent event) throws InterruptedException {
        log.warn("⌛ [Level9 Fixed] 模拟下游超时 - OrderId={}, Thread={}",
                event.getOrderId(), Thread.currentThread().getName());
        // Bug: 阻塞整个消费线程，造成积压
        Thread.sleep(3_000);
        throw new RuntimeException("下游系统超时");
    }

    private void randomFailure(Level9OrderEvent event) {
        if (random.nextBoolean()) {
            throw new RuntimeException("随机系统异常");
        }
    }

    static class MQServiceException extends RuntimeException {
        public MQServiceException(String message) {
            super(message);
        }
    }
}
