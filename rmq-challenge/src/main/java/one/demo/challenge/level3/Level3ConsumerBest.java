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

/**
 * Level 3 最佳实践版本：消息幂等性处理
 *
 * 核心改进：
 * 1. ✅ 使用消息去重服务（原子操作）
 * 2. ✅ 业务层幂等性保护（订单状态检查）
 * 3. ✅ 区分业务异常和系统异常
 * 4. ✅ 正确的异常处理策略
 * 5. ✅ 事务边界清晰
 *
 * 幂等性方案总结：
 * - 方案1：消息去重表（本例使用 MessageDeduplicationService）
 * - 方案2：业务状态机（检查订单是否已支付）
 * - 方案3：业务层幂等扣款（AccountServiceIdempotent）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "order-payment",
        tag = "payment-best",
        consumerGroup = "order-consumer-best",
        endpoints = "localhost:8080"
)
public class Level3ConsumerBest implements RocketMQListener {

    private final OrderService orderService;
    private final AccountServiceIdempotent accountService;
    private final MessageDeduplicationService deduplicationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ConsumeResult consume(MessageView messageView) {
        String messageId = messageView.getMessageId().toString();
        String messageBody = null;

        try {
            messageBody = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();
            log.info("收到支付消息 - MessageId: {}, Body: {}", messageId, messageBody);

            // 解析消息
            Map<String, Object> paymentInfo = objectMapper.readValue(messageBody, Map.class);
            String orderId = (String) paymentInfo.get("orderId");
            String userId = (String) paymentInfo.get("userId");
            BigDecimal amount = new BigDecimal(paymentInfo.get("amount").toString());

            // =========== 幂等性方案1：消息去重 ===========
            // 使用 messageId 作为去重键
            // 优点：通用，适用于所有场景
            // 缺点：需要额外存储
            if (!deduplicationService.tryProcess(messageId)) {
                log.info("重复消息已跳过 - MessageId: {}, OrderId: {}", messageId, orderId);
                return ConsumeResult.SUCCESS;  // 重复消息返回成功，不再重试
            }

            // =========== 幂等性方案2：业务状态机检查 ===========
            // 检查订单是否已经支付
            // 优点：利用业务状态，逻辑清晰
            // 缺点：需要每个业务场景单独实现
            if (orderService.isPaid(orderId)) {
                log.info("订单已支付，跳过处理 - OrderId: {}", orderId);
                return ConsumeResult.SUCCESS;
            }

            // 模拟业务处理耗时
            Thread.sleep(100);

            // =========== 幂等性方案3：业务层幂等操作 ===========
            // 扣款操作本身是幂等的（使用 orderId 作为幂等键）
            // 优点：多层防护，最可靠
            boolean deductSuccess = accountService.deductIdempotent(userId, amount, orderId);

            if (!deductSuccess) {
                log.error("扣款失败 - OrderId: {}, UserId: {}", orderId, userId);
                return ConsumeResult.FAILURE;  // 系统异常，重试
            }

            // 更新订单状态
            orderService.updateOrderToPaid(orderId);

            log.info("✅ 支付处理成功 - OrderId: {}, UserId: {}, Amount: {}",
                    orderId, userId, amount);

            return ConsumeResult.SUCCESS;

        } catch (IllegalArgumentException | ArithmeticException e) {
            // =========== 业务异常：不应该重试 ===========
            log.error("❌ 业务异常（不重试）- MessageId: {}, Body: {}, Error: {}",
                    messageId, messageBody, e.getMessage());

            // 业务异常返回 SUCCESS，避免无限重试
            // 可以将消息写入死信队列或告警
            return ConsumeResult.SUCCESS;

        } catch (RuntimeException e) {
            // 余额不足也是业务异常
            if (e.getMessage() != null && e.getMessage().contains("余额不足")) {
                log.error("❌ 余额不足（不重试）- MessageId: {}, Error: {}", messageId, e.getMessage());
                return ConsumeResult.SUCCESS;
            }

            // 其他运行时异常，返回 FAILURE 触发重试
            log.error("⚠️ 系统异常（将重试）- MessageId: {}, Error: {}", messageId, e.getMessage(), e);
            return ConsumeResult.FAILURE;

        } catch (Exception e) {
            // =========== 系统异常：应该重试 ===========
            log.error("⚠️ 系统异常（将重试）- MessageId: {}, Body: {}, Error: {}",
                    messageId, messageBody, e.getMessage(), e);

            // 系统异常返回 FAILURE，触发重试
            return ConsumeResult.FAILURE;
        }
    }
}