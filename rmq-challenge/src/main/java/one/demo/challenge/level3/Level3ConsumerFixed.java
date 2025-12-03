package one.demo.challenge.level3;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Level 3 挑战：消息重复消费问题
 *
 * 问题场景：
 * 订单支付成功后，发送 MQ 消息进行扣款。由于网络抖动或消费者重启，
 * 同一条消息可能被重复消费，导致用户被重复扣款！
 *
 * 问题现象：
 * 1. 用户支付一笔订单，但账户余额被扣了多次
 * 2. 订单状态被重复更新
 * 3. 业务数据不一致
 * 4. 用户投诉
 *
 * 任务：
 * 1. 理解为什么 RocketMQ 会重复消费
 * 2. 找出代码中缺少的幂等性保护
 * 3. 设计并实现幂等性方案
 *
 * 提示：
 * - RocketMQ 保证 At Least Once，不保证 Exactly Once
 * - 什么情况下会触发重复消费？
 * - 如何设计幂等性？数据库唯一索引？分布式锁？消息去重表？
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "order-payment",
        tag = "payment-fixed",
        consumerGroup = "order-consumer-fixed",
        endpoints = "localhost:8080"
)
public class Level3ConsumerFixed implements RocketMQListener {

    private static final Map<String, Object> CONSUMED_ORDER = new ConcurrentHashMap<>();

    private final OrderService orderService;
    private final AccountService accountService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ConsumeResult consume(MessageView messageView) {
        try {
            String messageBody = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();
            log.info("收到支付消息 - MessageId: {}, Body: {}",
                    messageView.getMessageId(), messageBody);

            // 解析消息
            Map<String, Object> paymentInfo = objectMapper.readValue(messageBody, Map.class);
            String orderId = (String) paymentInfo.get("orderId");
            String userId = (String) paymentInfo.get("userId");
            BigDecimal amount = new BigDecimal(paymentInfo.get("amount").toString());

            // 检查消息是否已经处理过（幂等性检查）
            if (!CONSUMED_ORDER.containsKey(orderId) && !orderService.isPaid(orderId)) {

                // 模拟业务处理耗时
                Thread.sleep(100);

                // 如果消息重复消费，用户会被重复扣款！
                accountService.deduct(userId, amount, orderId);

                // 更新订单状态
                orderService.updateOrderToPaid(orderId);

                log.info("支付处理成功 - OrderId: {}, UserId: {}, Amount: {}",
                        orderId, userId, amount);

                // 记录这条消息已经处理过
                CONSUMED_ORDER.put(orderId, true);
            } else {
                log.warn("消息重复处理 - MessageId: {}, OrderId: {}",
                        messageView.getMessageId(), orderId);
            }

            return ConsumeResult.SUCCESS;
        } catch (Exception e) {
            log.error("处理支付消息失败 - MessageId: {}, Error: {}",
                    messageView.getMessageId(), e.getMessage(), e);

            // Bug 5: 如果处理失败返回 FAILURE，消息会重新投递
            // 如果是业务异常（如余额不足），不应该重试，否则会一直重试
            // 如果部分操作已经执行（如已扣款但更新订单失败），重试会导致重复扣款
            return ConsumeResult.FAILURE;
        }
    }
}