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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Level 6 挑战：事务消息问题（Buggy 版本）
 *
 * 问题场景：
 * 用户下单后，需要完成三个操作：
 * 1. 创建订单（本地数据库）
 * 2. 扣减库存（下游服务，通过MQ通知）
 * 3. 增加积分（下游服务，通过MQ通知）
 *
 * 这三个操作必须保持一致性：要么全部成功，要么全部失败。
 *
 * 问题现象：
 * 1. 订单创建成功，但消息发送失败 → 库存未扣减，积分未增加
 * 2. 消息发送成功，但订单创建失败 → 库存被扣减，积分被增加，但订单不存在
 * 3. 订单创建过程中异常，但消息已发送 → 数据不一致
 * 4. 网络抖动导致消息重复发送 → 库存重复扣减，积分重复增加
 *
 * Bug 分析：
 * 1. 先创建订单，再发送消息 → 消息发送失败时，订单已创建
 * 2. 先发送消息，再创建订单 → 订单创建失败时，消息已发送
 * 3. 没有使用事务消息机制
 * 4. 本地事务和消息发送不是原子操作
 * 5. 缺少事务回查机制
 *
 * 任务：
 * 1. 运行测试，观察数据不一致的现象
 * 2. 分析为什么会出现不一致
 * 3. 理解事务消息的工作原理
 * 4. 实现事务消息解决方案
 *
 * 提示：
 * - RocketMQ 提供了事务消息机制
 * - 事务消息分为两个阶段：Half消息 + Commit/Rollback
 * - 需要实现本地事务执行器和事务状态回查
 */
@Slf4j
@RestController
@RequestMapping("/challenge/level6/buggy")
public class Level6ProducerBuggy {

    private static final String ENDPOINTS = "localhost:8081";
    private static final String TOPIC = "order-transaction-topic";

    @Autowired
    private L6OrderService l6OrderService;

    private Producer producer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() throws ClientException {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(ENDPOINTS)
                .setRequestTimeout(Duration.ofSeconds(3))
                .build();

        this.producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics(TOPIC)
                .build();

        log.info("✅ Level 6 Producer (Buggy) 初始化完成");
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
     * 方案1：先创建订单，再发送消息
     * Bug: 如果消息发送失败，订单已经创建，导致数据不一致
     */
    @GetMapping("/createOrder1")
    public String createOrderApproach1(
            @RequestParam String userId,
            @RequestParam String productId,
            @RequestParam Integer quantity,
            @RequestParam BigDecimal amount) {

        String orderId = "ORDER-" + UUID.randomUUID().toString().substring(0, 8);

        try {
            // 步骤1: 先创建订单（本地事务）
            L6Order l6Order = new L6Order(orderId, userId, productId, quantity, amount);
            l6OrderService.createOrder(l6Order);
            log.info("✅ [方案1] 订单创建成功 - OrderId: {}", orderId);

            // 步骤2: 再发送消息通知下游服务
            L6OrderEvent event = new L6OrderEvent(orderId, userId, productId, quantity, amount, "ORDER_CREATED");
            sendMessage(event);
            log.info("✅ [方案1] 消息发送成功 - OrderId: {}", orderId);

            return String.format("✅ 订单创建成功 - OrderId: %s\n\n" +
                    "⚠️ Bug提示：如果消息发送失败（网络异常、Broker宕机等），订单已创建但下游服务未收到通知！", orderId);

        } catch (Exception e) {
            log.error("❌ [方案1] 订单创建失败", e);
            return "❌ 订单创建失败: " + e.getMessage() +
                    "\n\n⚠️ Bug现象：订单可能已创建，但消息发送失败，数据不一致！";
        }
    }

    /**
     * 方案2：先发送消息，再创建订单
     * Bug: 如果订单创建失败，消息已经发送，导致数据不一致
     */
    @GetMapping("/createOrder2")
    public String createOrderApproach2(
            @RequestParam String userId,
            @RequestParam String productId,
            @RequestParam Integer quantity,
            @RequestParam BigDecimal amount) {

        String orderId = "ORDER-" + UUID.randomUUID().toString().substring(0, 8);

        try {
            // 步骤1: 先发送消息
            L6OrderEvent event = new L6OrderEvent(orderId, userId, productId, quantity, amount, "ORDER_CREATED");
            sendMessage(event);
            log.info("✅ [方案2] 消息发送成功 - OrderId: {}", orderId);

            // 步骤2: 再创建订单（本地事务）
            L6Order l6Order = new L6Order(orderId, userId, productId, quantity, amount);
            l6OrderService.createOrder(l6Order);
            log.info("✅ [方案2] 订单创建成功 - OrderId: {}", orderId);

            return String.format("✅ 订单创建成功 - OrderId: %s\n\n" +
                    "⚠️ Bug提示：如果订单创建失败（数据库异常、业务校验失败等），消息已发送但订单不存在！", orderId);

        } catch (Exception e) {
            log.error("❌ [方案2] 订单创建失败", e);
            return "❌ 订单创建失败: " + e.getMessage() +
                    "\n\n⚠️ Bug现象：消息可能已发送，但订单创建失败，下游服务会处理不存在的订单！";
        }
    }

    /**
     * 方案3：使用try-catch包裹，失败时回滚
     * Bug: 无法保证原子性，中间状态可能被观察到
     */
    @GetMapping("/createOrder3")
    public String createOrderApproach3(
            @RequestParam String userId,
            @RequestParam String productId,
            @RequestParam Integer quantity,
            @RequestParam BigDecimal amount) {

        String orderId = "ORDER-" + UUID.randomUUID().toString().substring(0, 8);

        try {
            // 步骤1: 创建订单
            L6Order l6Order = new L6Order(orderId, userId, productId, quantity, amount);
            l6OrderService.createOrder(l6Order);
            log.info("✅ [方案3] 订单创建成功 - OrderId: {}", orderId);

            try {
                // 步骤2: 发送消息
                L6OrderEvent event = new L6OrderEvent(orderId, userId, productId, quantity, amount, "ORDER_CREATED");
                sendMessage(event);
                log.info("✅ [方案3] 消息发送成功 - OrderId: {}", orderId);

            } catch (Exception e) {
                // 步骤3: 消息发送失败，回滚订单
                log.error("❌ [方案3] 消息发送失败，尝试回滚订单", e);
                l6OrderService.cancelOrder(orderId);
                throw new RuntimeException("消息发送失败，订单已回滚", e);
            }

            return String.format("✅ 订单创建成功 - OrderId: %s\n\n" +
                    "⚠️ Bug提示：回滚操作本身可能失败！而且在回滚之前，订单的中间状态可能被其他线程观察到！", orderId);

        } catch (Exception e) {
            log.error("❌ [方案3] 订单创建失败", e);
            return "❌ 订单创建失败: " + e.getMessage() +
                    "\n\n⚠️ Bug现象：回滚操作可能失败，或者中间状态被观察到，无法保证原子性！";
        }
    }

    /**
     * 发送普通消息
     */
    private void sendMessage(L6OrderEvent event) throws Exception {
        String messageBody = objectMapper.writeValueAsString(event);

        ClientServiceProvider provider = ClientServiceProvider.loadService();
        Message message = provider.newMessageBuilder()
                .setTopic(TOPIC)
                .setTag("order-event")
                .setKeys(event.getOrderId())
                .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
                .build();

        SendReceipt receipt = producer.send(message);
        log.info("📤 发送消息 - OrderId: {}, MessageId: {}", event.getOrderId(), receipt.getMessageId());
    }

    /**
     * 模拟消息发送失败的场景
     */
    @GetMapping("/simulateMessageFailure")
    public String simulateMessageFailure(
            @RequestParam String userId,
            @RequestParam String productId,
            @RequestParam Integer quantity,
            @RequestParam BigDecimal amount) {

        String orderId = "ORDER-" + UUID.randomUUID().toString().substring(0, 8);

        try {
            // 创建订单
            L6Order l6Order = new L6Order(orderId, userId, productId, quantity, amount);
            l6OrderService.createOrder(l6Order);
            log.info("✅ 订单创建成功 - OrderId: {}", orderId);

            // 模拟消息发送失败
            log.error("❌ 模拟消息发送失败 - OrderId: {}", orderId);
            throw new RuntimeException("模拟网络异常：连接 Broker 超时");

        } catch (Exception e) {
            return String.format("❌ 消息发送失败 - OrderId: %s\n\n" +
                    "🔍 检查数据一致性：\n" +
                    "curl \"http://localhost:8070/challenge/level6/checkOrder?orderId=%s\"\n\n" +
                    "⚠️ Bug现象：订单已创建，但消息未发送，库存和积分未变化！", orderId, orderId);
        }
    }

    /**
     * 模拟订单创建失败的场景
     */
    @GetMapping("/simulateOrderFailure")
    public String simulateOrderFailure(
            @RequestParam String userId,
            @RequestParam String productId,
            @RequestParam Integer quantity,
            @RequestParam BigDecimal amount) {

        String orderId = "ORDER-" + UUID.randomUUID().toString().substring(0, 8);

        try {
            // 先发送消息
            L6OrderEvent event = new L6OrderEvent(orderId, userId, productId, quantity, amount, "ORDER_CREATED");
            sendMessage(event);
            log.info("✅ 消息发送成功 - OrderId: {}", orderId);

            // 模拟订单创建失败
            log.error("❌ 模拟订单创建失败 - OrderId: {}", orderId);
            throw new RuntimeException("模拟数据库异常：订单表锁超时");

        } catch (Exception e) {
            return String.format("❌ 订单创建失败 - OrderId: %s\n\n" +
                    "🔍 检查数据一致性：\n" +
                    "curl \"http://localhost:8070/challenge/level6/checkOrder?orderId=%s\"\n\n" +
                    "⚠️ Bug现象：消息已发送，但订单不存在，下游服务会处理不存在的订单！", orderId, orderId);
        }
    }
}
