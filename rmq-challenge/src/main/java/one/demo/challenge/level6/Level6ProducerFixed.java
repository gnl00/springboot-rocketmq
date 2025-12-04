package one.demo.challenge.level6;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.apis.producer.Transaction;
import org.apache.rocketmq.client.apis.producer.TransactionChecker;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Level 6 挑战：事务消息问题（Fixed 版本）
 *
 * 解决方案：使用 RocketMQ 事务消息
 *
 * 事务消息工作流程：
 * 1. 发送 Half 消息（对消费者不可见）
 * 2. 执行本地事务（创建订单）
 * 3. 根据本地事务结果，Commit 或 Rollback 消息
 * 4. 如果长时间未收到确认，Broker 会回查事务状态
 *
 * 关键点：
 * 1. 本地事务和消息发送的最终一致性
 * 2. 事务状态回查机制
 * 3. 幂等性保证
 */
@Slf4j
// @RestController
@RequestMapping("/challenge/level6/fixed")
public class Level6ProducerFixed {

    private static final String ENDPOINTS = "localhost:8081";
    private static final String TOPIC = "order-transaction-topic";

    @Autowired
    private L6OrderService l6OrderService;

    private Producer producer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 存储事务状态，用于回查（生产环境应该使用数据库）
    private final Map<String, TransactionStatus> transactionStatusMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() throws ClientException {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(ENDPOINTS)
                .setRequestTimeout(Duration.ofSeconds(3))
                .build();

        // 创建事务检查器
        TransactionChecker checker = messageView -> {
            String transactionId = messageView.getMessageId().toString();
            log.info("🔍 [事务回查] TransactionId: {}", transactionId);

            // 从存储中查询事务状态
            TransactionStatus status = transactionStatusMap.get(transactionId);

            if (status == null) {
                log.warn("⚠️ [事务回查] 事务状态未知，返回 UNKNOWN");
                return TransactionResolution.UNKNOWN;
            }

            switch (status) {
                case COMMITTED:
                    log.info("✅ [事务回查] 事务已提交，返回 COMMIT");
                    return TransactionResolution.COMMIT;
                case ROLLBACK:
                    log.info("❌ [事务回查] 事务已回滚，返回 ROLLBACK");
                    return TransactionResolution.ROLLBACK;
                default:
                    log.warn("⚠️ [事务回查] 事务状态未知，返回 UNKNOWN");
                    return TransactionResolution.UNKNOWN;
            }
        };

        // 创建支持事务的 Producer
        this.producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics(TOPIC)
                .setTransactionChecker(checker)
                .build();

        log.info("✅ Level 6 Producer (Fixed) 初始化完成");
    }

    @PreDestroy
    public void destroy() {
        if (producer != null) {
            try {
                producer.close();
            } catch (Exception e) {
                log.error("关闭 Producer 失败", e);
            }
        }
    }

    /**
     * 使用事务消息创建订单
     */
    @GetMapping("/createOrder")
    public String createOrder(
            @RequestParam String userId,
            @RequestParam String productId,
            @RequestParam Integer quantity,
            @RequestParam BigDecimal amount) {

        String orderId = "ORDER-" + UUID.randomUUID().toString().substring(0, 8);

        try {
            // 准备消息
            L6OrderEvent event = new L6OrderEvent(orderId, userId, productId, quantity, amount, "ORDER_CREATED");
            String messageBody = objectMapper.writeValueAsString(event);

            ClientServiceProvider provider = ClientServiceProvider.loadService();
            Message message = provider.newMessageBuilder()
                    .setTopic(TOPIC)
                    .setTag("order-event")
                    .setKeys(orderId)
                    .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
                    .build();

            // 开始事务
            Transaction transaction = producer.beginTransaction();
            String transactionId = transaction.toString(); // 简化处理，实际应该用更可靠的ID

            log.info("🚀 [事务消息] 开始发送 - OrderId: {}, TransactionId: {}", orderId, transactionId);

            try {
                // 步骤1: 发送 Half 消息（对消费者不可见）
                SendReceipt receipt = producer.send(message, transaction);
                log.info("📤 [事务消息] Half 消息已发送 - MessageId: {}", receipt.getMessageId());

                // 步骤2: 执行本地事务（创建订单）
                L6Order l6Order = new L6Order(orderId, userId, productId, quantity, amount);
                l6OrderService.createOrder(l6Order);
                log.info("✅ [本地事务] 订单创建成功 - OrderId: {}", orderId);

                // 步骤3: 提交事务（消息对消费者可见）
                transaction.commit();
                transactionStatusMap.put(transactionId, TransactionStatus.COMMITTED);
                log.info("✅ [事务消息] 事务已提交 - OrderId: {}", orderId);

                return String.format("✅ 订单创建成功 - OrderId: %s\n\n" +
                        "💡 事务消息保证：\n" +
                        "- 本地事务成功 → 消息一定发送\n" +
                        "- 本地事务失败 → 消息一定不发送\n" +
                        "- 保证最终一致性", orderId);

            } catch (Exception e) {
                // 步骤3: 回滚事务（消息不会被消费）
                log.error("❌ [本地事务] 订单创建失败，回滚事务 - OrderId: {}", orderId, e);
                transaction.rollback();
                transactionStatusMap.put(transactionId, TransactionStatus.ROLLBACK);
                log.info("❌ [事务消息] 事务已回滚 - OrderId: {}", orderId);

                return String.format("❌ 订单创建失败 - OrderId: %s\n\n" +
                        "💡 事务消息保证：\n" +
                        "- 本地事务失败，消息已回滚\n" +
                        "- 下游服务不会收到消息\n" +
                        "- 数据保持一致", orderId);
            }

        } catch (Exception e) {
            log.error("❌ 订单创建失败", e);
            return "❌ 订单创建失败: " + e.getMessage();
        }
    }

    /**
     * 模拟本地事务失败的场景
     */
    @GetMapping("/simulateLocalTransactionFailure")
    public String simulateLocalTransactionFailure(
            @RequestParam String userId,
            @RequestParam String productId,
            @RequestParam Integer quantity,
            @RequestParam BigDecimal amount) {

        String orderId = "ORDER-" + UUID.randomUUID().toString().substring(0, 8);

        try {
            // 准备消息
            L6OrderEvent event = new L6OrderEvent(orderId, userId, productId, quantity, amount, "ORDER_CREATED");
            String messageBody = objectMapper.writeValueAsString(event);

            ClientServiceProvider provider = ClientServiceProvider.loadService();
            Message message = provider.newMessageBuilder()
                    .setTopic(TOPIC)
                    .setTag("order-event")
                    .setKeys(orderId)
                    .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
                    .build();

            // 开始事务
            Transaction transaction = producer.beginTransaction();
            String transactionId = transaction.toString();

            try {
                // 发送 Half 消息
                SendReceipt receipt = producer.send(message, transaction);
                log.info("📤 [事务消息] Half 消息已发送 - MessageId: {}", receipt.getMessageId());

                // 模拟本地事务失败
                log.error("❌ [模拟] 本地事务失败 - OrderId: {}", orderId);
                throw new RuntimeException("模拟数据库异常：订单表锁超时");

            } catch (Exception e) {
                // 回滚事务
                transaction.rollback();
                transactionStatusMap.put(transactionId, TransactionStatus.ROLLBACK);
                log.info("❌ [事务消息] 事务已回滚 - OrderId: {}", orderId);

                return String.format("❌ 本地事务失败，事务已回滚 - OrderId: %s\n\n" +
                        "🔍 检查数据一致性：\n" +
                        "curl \"http://localhost:8070/challenge/level6/checkAll\"\n\n" +
                        "✅ 预期结果：订单不存在，库存和积分未变化（数据一致）", orderId);
            }

        } catch (Exception e) {
            log.error("❌ 订单创建失败", e);
            return "❌ 订单创建失败: " + e.getMessage();
        }
    }

    /**
     * 事务状态枚举
     */
    private enum TransactionStatus {
        COMMITTED,
        ROLLBACK,
        UNKNOWN
    }
}
