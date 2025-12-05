package one.demo.challenge.level9;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Level9 Producer（Buggy）：
 * - 没有业务异常与系统异常的区分；
 * - 完全依赖 Broker 默认重试/死信策略；
 * - 没有提供任何死信消息的回查接口。
 */
@Slf4j
@RestController
@RequestMapping("/challenge/level9")
public class Level9ProducerBuggy {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();
    private final Level9OrderService orderService;

    private Producer producer;

    public Level9ProducerBuggy(Level9OrderService orderService) {
        this.orderService = orderService;
    }

    @PostConstruct
    public void init() throws ClientException {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(Level9Constants.ENDPOINTS)
                .setRequestTimeout(Duration.ofSeconds(3))
                .build();

        this.producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics(Level9Constants.ORDER_TOPIC)
                .build();

        log.info("✅ Level9 Producer (Buggy) 初始化完成，Topic={}", Level9Constants.ORDER_TOPIC);
    }

    @PreDestroy
    public void destroy() {
        if (producer != null) {
            try {
                producer.close();
            } catch (Exception e) {
                log.error("关闭 Level9 Producer 失败", e);
            }
        }
    }

    @GetMapping("/sendOrder")
    public String sendOrder(@RequestParam(defaultValue = "normal") String mode,
                            @RequestParam(required = false) String orderId,
                            @RequestParam(required = false) String userId,
                            @RequestParam(required = false) BigDecimal amount) {
        Level9ProcessingMode processingMode = Level9ProcessingMode.fromParam(mode);
        String finalOrderId = orderId != null ? orderId : "L9-" + UUID.randomUUID().toString().substring(0, 8);
        String finalUserId = userId != null ? userId : "USER-" + (100 + random.nextInt(900));
        BigDecimal finalAmount = amount != null ? amount : BigDecimal.valueOf(10 + random.nextInt(90));

        orderService.createOrder(finalOrderId, finalUserId, finalAmount, processingMode);
        Level9OrderEvent event = new Level9OrderEvent(finalOrderId, finalUserId, finalAmount, processingMode);

        try {
            Message message = ClientServiceProvider.loadService()
                    .newMessageBuilder()
                    .setTopic(Level9Constants.ORDER_TOPIC)
                    .setKeys(finalOrderId)
                    // Buggy：没有设置任何属性来区分业务异常与系统异常
                    .setBody(objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8))
                    .build();

            SendReceipt receipt = producer.send(message);
            log.info("📤 [Level9 Buggy] 发送消息 - OrderId={}, Mode={}, MessageId={}",
                    finalOrderId, processingMode, receipt.getMessageId());

            return String.format("""
                    ✅ Level9 消息已发送（Buggy）
                    - OrderId: %s
                    - Mode: %s
                    - Amount: %s
                    - MessageId: %s

                    ⚠️ Bug 提示：
                    1. 不区分业务异常/系统异常，消费失败将无限重试。
                    2. 未配置死信监控，重试耗尽后消息静默进入 DLQ。
                    3. Producer 未提供重试次数、退避间隔等配置。
                    """, finalOrderId, processingMode, finalAmount, receipt.getMessageId());

        } catch (Exception e) {
            log.error("❌ Level9 消息发送失败", e);
            return "❌ 发送失败: " + e.getMessage();
        }
    }

    @GetMapping("/checkOrder")
    public String checkOrder(@RequestParam String orderId) throws JsonProcessingException {
        return orderService.getOrder(orderId)
                .map(order -> {
                    try {
                        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(order);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                })
                .orElse("未找到订单：" + orderId);
    }

    @GetMapping("/checkAll")
    public String checkAll() {
        if (orderService.listOrders().isEmpty()) {
            return "暂无 Level9 订单，先调用 /sendOrder 吧。";
        }
        return orderService.listOrders().stream()
                .map(order -> String.format("OrderId=%s, Status=%s, Attempts=%d, Mode=%s",
                        order.getOrderId(), order.getStatus(), order.getConsumedAttempts(), order.getMode()))
                .collect(Collectors.joining("\n"));
    }

    @GetMapping("/reset")
    public String reset() {
        orderService.reset();
        return "🔄 Level9 数据已清空。";
    }

    @GetMapping("/help")
    public String help() {
        return """
                🆘 Level9 死信队列与重试（Buggy）
                1. 发送正常订单：
                   curl "http://localhost:8070/challenge/level9/sendOrder?mode=normal&orderId=L9-001"

                2. 模拟业务异常（负金额）：
                   curl "http://localhost:8070/challenge/level9/sendOrder?mode=business_error&amount=-10&orderId=L9-BIZ"

                3. 模拟系统异常（超时）：
                   curl "http://localhost:8070/challenge/level9/sendOrder?mode=system_timeout&orderId=L9-SYS"

                4. 查看消费状态：
                   curl "http://localhost:8070/challenge/level9/checkAll"

                ⚠️ Bug 说明：
                - 所有异常统一返回 FAILURE，触发无限制重试；
                - 未设置 maxReconsumeTimes，消息会一直重试直至 Broker 强制进入 DLQ；
                - 没有订阅 %DLQ% 队列，死信消息无人处理；
                - 没有提供任何重试退避配置。
                """;
    }
}
