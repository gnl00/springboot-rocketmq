package one.demo.challenge.level3;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Level 3 生产者：模拟订单支付场景
 */
@Slf4j
@RestController
@RequestMapping("/challenge/level3")
public class Level3Producer {

    private static final String ENDPOINTS = "localhost:8080";
    private static final String TOPIC = "order-payment";

    private Producer producer;
    private final OrderService orderService;
    private final AccountService accountService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Level3Producer(OrderService orderService, AccountService accountService) {
        this.orderService = orderService;
        this.accountService = accountService;
    }

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

        log.info("Level 3 Producer 初始化完成");
    }

    private static final Lock MOCK_DISTRIBUTED_LOCK = new ReentrantLock();

    /**
     * 模拟用户支付订单
     *
     * 步骤：
     * 1. 创建订单
     * 2. 发送支付消息到 MQ
     * 3. 消费者收到消息后扣款
     */
    @GetMapping("/payOrder")
    public String payOrder(@RequestParam(defaultValue = "user001") String userId,
                          @RequestParam(defaultValue = "100.00") String amount) {
        MOCK_DISTRIBUTED_LOCK.lock(); // lock userId for creating order
        try {
            String orderId = "ORDER-" + System.currentTimeMillis() / 1000 / 60; // 一分钟内的订单 id 相同，模拟相同订单

            // 1. 创建订单
            Order order = orderService.createOrder(orderId, userId, new BigDecimal(amount));

            // 2. 发送支付消息
            Map<String, Object> paymentInfo = new HashMap<>();
            paymentInfo.put("orderId", orderId);
            paymentInfo.put("userId", userId);
            paymentInfo.put("amount", amount);
            paymentInfo.put("timestamp", LocalDateTime.now().toString());

            String messageBody = objectMapper.writeValueAsString(paymentInfo);

            Message message = ClientServiceProvider.loadService()
                    .newMessageBuilder()
                    .setTopic(TOPIC)
                    .setTag("payment-fixed")
                    .setKeys(orderId)
                    .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
                    .build();

            SendReceipt receipt = producer.send(message);

            log.info("支付消息发送成功 - OrderId: {}, MessageId: {}", orderId, receipt.getMessageId());

            return String.format("支付订单成功 - OrderId: %s, MessageId: %s, 等待扣款处理...",
                    orderId, receipt.getMessageId());

        } catch (Exception e) {
            log.error("支付订单失败", e);
            return "支付失败: " + e.getMessage();
        } finally {
            MOCK_DISTRIBUTED_LOCK.unlock();
        }
    }

    /**
     * 【测试工具】模拟消息重复发送（模拟重复消费场景）
     *
     * 这个接口用于测试：如果同一条支付消息被发送多次，会发生什么？
     * 在真实场景中，重复消费可能由以下原因导致：
     * - 消费者 ACK 超时
     * - 消费者进程崩溃后重启
     * - 网络闪断
     * - Broker 重新投递
     */
    @GetMapping("/simulateDuplicateMessage")
    public String simulateDuplicateMessage(@RequestParam String orderId,
                                          @RequestParam(defaultValue = "3") int times) {
        try {
            Order order = orderService.getOrder(orderId);
            if (order == null) {
                return "订单不存在: " + orderId;
            }

            // 模拟发送多次相同的支付消息
            for (int i = 0; i < times; i++) {
                Map<String, Object> paymentInfo = new HashMap<>();
                paymentInfo.put("orderId", orderId);
                paymentInfo.put("userId", order.getUserId());
                paymentInfo.put("amount", order.getAmount().toString());
                paymentInfo.put("timestamp", LocalDateTime.now().toString());
                paymentInfo.put("duplicateSimulation", i + 1);  // 标记这是第几次重复

                String messageBody = objectMapper.writeValueAsString(paymentInfo);

                Message message = ClientServiceProvider.loadService()
                        .newMessageBuilder()
                        .setTopic(TOPIC)
                        .setTag("payment-duplicate")
                        .setKeys(orderId + "-dup-" + i)
                        .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
                        .build();

                SendReceipt receipt = producer.send(message);

                log.warn("【测试】发送重复消息 #{} - OrderId: {}, MessageId: {}",
                        i + 1, orderId, receipt.getMessageId());

                // 稍微间隔一下，让消费者能处理
                Thread.sleep(50);
            }

            return String.format("已发送 %d 条重复消息 - OrderId: %s。请观察账户余额是否被重复扣款！",
                    times, orderId);

        } catch (Exception e) {
            log.error("模拟重复消息失败", e);
            return "模拟失败: " + e.getMessage();
        }
    }

    /**
     * 查询订单状态
     */
    @GetMapping("/getOrder")
    public Order getOrder(@RequestParam String orderId) {
        return orderService.getOrder(orderId);
    }

    /**
     * 查询账户余额
     */
    @GetMapping("/getBalance")
    public String getBalance(@RequestParam String userId) {
        BigDecimal balance = accountService.getBalance(userId);
        return String.format("用户 %s 的余额: %s 元", userId, balance);
    }

    /**
     * 查询所有账户余额（用于观察重复扣款）
     */
    @GetMapping("/getAllBalances")
    public Map<String, BigDecimal> getAllBalances() {
        return accountService.getAllBalances();
    }

    /**
     * 查询所有订单
     */
    @GetMapping("/getAllOrders")
    public Map<String, Order> getAllOrders() {
        return orderService.getAllOrders();
    }
}