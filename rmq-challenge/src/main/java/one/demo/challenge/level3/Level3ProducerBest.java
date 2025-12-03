package one.demo.challenge.level3;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Level 3 最佳实践生产者：测试幂等性方案
 */
@Slf4j
@RestController
@RequestMapping("/challenge/level3/best")
@RequiredArgsConstructor
public class Level3ProducerBest {

    private static final String ENDPOINTS = "localhost:8080";
    private static final String TOPIC = "order-payment";

    private Producer producer;
    private final OrderService orderService;
    private final AccountServiceIdempotent accountService;
    private final MessageDeduplicationService deduplicationService;
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
                .build();

        log.info("Level 3 Best Producer 初始化完成");
    }

    /**
     * 支付订单（使用幂等消费者）
     */
    @GetMapping("/payOrder")
    public String payOrder(@RequestParam(defaultValue = "user001") String userId,
                          @RequestParam(defaultValue = "100.00") String amount) {
        try {
            String orderId = "ORDER-" + System.currentTimeMillis();

            // 1. 创建订单
            Order order = orderService.createOrder(orderId, userId, new BigDecimal(amount));

            // 2. 发送支付消息（tag 为 payment-best）
            Map<String, Object> paymentInfo = new HashMap<>();
            paymentInfo.put("orderId", orderId);
            paymentInfo.put("userId", userId);
            paymentInfo.put("amount", amount);
            paymentInfo.put("timestamp", LocalDateTime.now().toString());

            String messageBody = objectMapper.writeValueAsString(paymentInfo);

            Message message = ClientServiceProvider.loadService()
                    .newMessageBuilder()
                    .setTopic(TOPIC)
                    .setTag("payment-best")  // 使用 best 版本的 tag
                    .setKeys(orderId)
                    .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
                    .build();

            SendReceipt receipt = producer.send(message);

            log.info("支付消息发送成功 - OrderId: {}, MessageId: {}", orderId, receipt.getMessageId());

            return String.format("✅ 支付订单成功 - OrderId: %s, MessageId: %s",
                    orderId, receipt.getMessageId());

        } catch (Exception e) {
            log.error("支付订单失败", e);
            return "❌ 支付失败: " + e.getMessage();
        }
    }

    /**
     * 模拟重复消息发送（测试幂等性）
     */
    @GetMapping("/simulateDuplicate")
    public String simulateDuplicate(@RequestParam String orderId,
                                   @RequestParam(defaultValue = "3") int times) {
        try {
            Order order = orderService.getOrder(orderId);
            if (order == null) {
                return "❌ 订单不存在: " + orderId;
            }

            // 发送多次相同的支付消息
            for (int i = 0; i < times; i++) {
                Map<String, Object> paymentInfo = new HashMap<>();
                paymentInfo.put("orderId", orderId);
                paymentInfo.put("userId", order.getUserId());
                paymentInfo.put("amount", order.getAmount().toString());
                paymentInfo.put("timestamp", LocalDateTime.now().toString());
                paymentInfo.put("duplicate", i + 1);

                String messageBody = objectMapper.writeValueAsString(paymentInfo);

                Message message = ClientServiceProvider.loadService()
                        .newMessageBuilder()
                        .setTopic(TOPIC)
                        .setTag("payment-best")
                        .setKeys(orderId + "-dup-" + i)
                        .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
                        .build();

                SendReceipt receipt = producer.send(message);

                log.info("📨 发送重复消息 #{} - OrderId: {}, MessageId: {}",
                        i + 1, orderId, receipt.getMessageId());

                Thread.sleep(50);
            }

            return String.format("✅ 已发送 %d 条重复消息 - OrderId: %s。" +
                    "\n请查看余额是否被正确保护（应该只扣一次款）！", times, orderId);

        } catch (Exception e) {
            log.error("模拟重复消息失败", e);
            return "❌ 模拟失败: " + e.getMessage();
        }
    }

    /**
     * 查询账户余额
     */
    @GetMapping("/getBalance")
    public String getBalance(@RequestParam String userId) {
        BigDecimal balance = accountService.getBalance(userId);
        return String.format("💰 用户 %s 的余额: %s 元", userId, balance);
    }

    /**
     * 查询所有账户余额
     */
    @GetMapping("/getAllBalances")
    public Map<String, BigDecimal> getAllBalances() {
        return accountService.getAllBalances();
    }

    /**
     * 查询订单状态
     */
    @GetMapping("/getOrder")
    public Order getOrder(@RequestParam String orderId) {
        return orderService.getOrder(orderId);
    }

    /**
     * 查询去重统计
     */
    @GetMapping("/deduplicationStats")
    public String getDeduplicationStats() {
        int processedCount = deduplicationService.getProcessedCount();
        return String.format("📊 去重统计 - 已处理消息数: %d", processedCount);
    }

    /**
     * 完整测试流程
     */
    @GetMapping("/fullTest")
    public String fullTest() {
        StringBuilder result = new StringBuilder();

        try {
            // 1. 查看初始余额
            BigDecimal initialBalance = accountService.getBalance("user001");
            result.append(String.format("1️⃣ 初始余额: user001 = %s 元\n", initialBalance));

            // 2. 支付订单
            String orderId = "ORDER-" + System.currentTimeMillis();
            Order order = orderService.createOrder(orderId, "user001", new BigDecimal("100.00"));

            Map<String, Object> paymentInfo = new HashMap<>();
            paymentInfo.put("orderId", orderId);
            paymentInfo.put("userId", "user001");
            paymentInfo.put("amount", "100.00");

            String messageBody = objectMapper.writeValueAsString(paymentInfo);
            Message message = ClientServiceProvider.loadService()
                    .newMessageBuilder()
                    .setTopic(TOPIC)
                    .setTag("payment-best")
                    .setKeys(orderId)
                    .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
                    .build();

            producer.send(message);
            result.append(String.format("2️⃣ 已发送支付消息 - OrderId: %s\n", orderId));

            // 3. 等待消费
            Thread.sleep(500);

            // 4. 查看余额
            BigDecimal balanceAfterFirst = accountService.getBalance("user001");
            result.append(String.format("3️⃣ 第一次扣款后余额: %s 元\n", balanceAfterFirst));

            // 5. 发送重复消息
            result.append("4️⃣ 发送 5 条重复消息...\n");
            for (int i = 0; i < 5; i++) {
                Message dupMessage = ClientServiceProvider.loadService()
                        .newMessageBuilder()
                        .setTopic(TOPIC)
                        .setTag("payment-best")
                        .setKeys(orderId + "-dup-" + i)
                        .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
                        .build();
                producer.send(dupMessage);
                Thread.sleep(50);
            }

            // 6. 等待消费
            Thread.sleep(1000);

            // 7. 查看最终余额
            BigDecimal finalBalance = accountService.getBalance("user001");
            result.append(String.format("5️⃣ 重复消息处理后余额: %s 元\n", finalBalance));

            // 8. 验证结果
            BigDecimal expectedBalance = initialBalance.subtract(new BigDecimal("100.00"));
            if (finalBalance.compareTo(expectedBalance) == 0) {
                result.append("\n✅ 测试通过！余额正确，幂等性保护生效！\n");
                result.append(String.format("   预期余额: %s 元，实际余额: %s 元\n", expectedBalance, finalBalance));
            } else {
                result.append("\n❌ 测试失败！余额不正确，发生重复扣款！\n");
                result.append(String.format("   预期余额: %s 元，实际余额: %s 元\n", expectedBalance, finalBalance));
            }

            return result.toString();

        } catch (Exception e) {
            log.error("完整测试失败", e);
            return result.append("\n❌ 测试异常: " + e.getMessage()).toString();
        }
    }
}