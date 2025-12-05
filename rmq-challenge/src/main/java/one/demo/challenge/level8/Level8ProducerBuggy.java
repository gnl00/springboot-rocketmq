package one.demo.challenge.level8;

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
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Level 8 Producer（Buggy 版本）
 *
 * Bug 场景：
 * 1. 没有设置 Tag，导致所有消费者都收到所有消息；
 * 2. Tag 拼写不一致，秒杀消费者收不到消息；
 * 3. SQL 过滤表达式错误，消费者无法启动；
 * 4. 过滤逻辑放在消费者端，性能下降。
 */
@Slf4j
@RestController
@RequestMapping("/challenge/level8")
public class Level8ProducerBuggy {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    private Producer producer;

    // 统计接口用于在 HTTP 层展示消费情况
    private final Level8ConsumerStatsService statsService;

    public Level8ProducerBuggy(Level8ConsumerStatsService statsService) {
        this.statsService = statsService;
    }

    @PostConstruct
    public void init() throws ClientException {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(Level8Constants.ENDPOINTS)
                .setRequestTimeout(Duration.ofSeconds(3))
                .build();

        this.producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics(Level8Constants.ORDER_TOPIC)
                .build();

        log.info("✅ Level 8 Producer (Buggy) 初始化完成，Topic={}", Level8Constants.ORDER_TOPIC);
    }

    @PreDestroy
    public void destroy() {
        if (producer != null) {
            try {
                producer.close();
            } catch (Exception e) {
                log.error("关闭 Level8 Producer 失败", e);
            }
        }
    }

    /**
     * 入口 1：发送不同类型的订单。Bug：没有设置 Tag。
     */
    @GetMapping("/sendOrder")
    public String sendOrder(@RequestParam(defaultValue = "normal") String type,
                            @RequestParam(required = false) String orderId) {
        Level8OrderType orderType = Level8OrderType.fromRequest(type);
        String finalOrderId = resolveOrderId(orderId, orderType);

        Level8OrderMessage orderMessage = Level8OrderMessage.of(
                orderType,
                finalOrderId,
                randomAmount(orderType),
                randomRegion(),
                randomUserId(),
                resolveUserLevel(orderType)
        );

        try {
            // Bug：没有设置任何 Tag，所有消费者都会收到所有消息
            Message message = ClientServiceProvider.loadService()
                    .newMessageBuilder()
                    .setTopic(Level8Constants.ORDER_TOPIC)
                    .setKeys(orderMessage.getOrderId())
                    .setBody(objectMapper.writeValueAsBytes(orderMessage))
                    .build();

            SendReceipt receipt = producer.send(message);
            log.info("📤 [Buggy] 发送订单消息 - OrderId={}, Type={}, MessageId={}",
                    orderMessage.getOrderId(), orderMessage.getOrderType(), receipt.getMessageId());

            return String.format("""
                    ✅ [Buggy] 订单消息已发送
                    - OrderId: %s
                    - OrderType: %s
                    - Amount: %s
                    - Region: %s
                    ⚠️ Bug: 没有设置 Tag，所有消费者都会收到所有订单。

                    试试：
                    curl "http://localhost:8070/challenge/level8/sendOrder?type=normal&orderId=ORDER-001"
                    curl "http://localhost:8070/challenge/level8/sendOrder?type=seckill&orderId=ORDER-002"
                    curl "http://localhost:8070/challenge/level8/checkConsumerStats"
                    """,
                    orderMessage.getOrderId(),
                    orderMessage.getOrderType(),
                    orderMessage.getAmount(),
                    orderMessage.getRegion());

        } catch (Exception e) {
            log.error("❌ 发送 Level8 消息失败", e);
            return "❌ 发送失败: " + e.getMessage();
        }
    }

    /**
     * 入口 2：模拟 Tag 拼写错误场景。
     * 生产者将 Tag 设置为 "seckill-order"，而消费者订阅 "seckill_order"。
     */
    @GetMapping("/buggy/sendOrder")
    public String sendOrderWithWrongTag(@RequestParam(defaultValue = "seckill") String type,
                                        @RequestParam(required = false) String orderId) {
        Level8OrderType orderType = Level8OrderType.fromRequest(type);
        String finalOrderId = resolveOrderId(orderId, orderType);

        Level8OrderMessage orderMessage = Level8OrderMessage.of(
                orderType,
                finalOrderId,
                randomAmount(orderType),
                "beijing",
                randomUserId(),
                "VIP"
        );

        try {
            // Bug：Tag 使用连字符，消费者订阅使用下划线，导致无法匹配
            Message message = ClientServiceProvider.loadService()
                    .newMessageBuilder()
                    .setTopic(Level8Constants.ORDER_TOPIC)
                    .setTag(orderType.getDefaultTag()) // seckill-order
                    .setKeys(orderMessage.getOrderId())
                    .setBody(objectMapper.writeValueAsBytes(orderMessage))
                    .build();

            SendReceipt receipt = producer.send(message);
            log.info("📤 [Buggy] 发送带 Tag 的订单 - OrderId={}, Tag={}, MessageId={}",
                    orderMessage.getOrderId(), orderType.getDefaultTag(), receipt.getMessageId());

            return String.format("""
                    ✅ [Buggy] 秒杀订单已发送
                    - OrderId: %s
                    - Tag: %s
                    ⚠️ Bug: 消费者订阅的是 seckill_order（下划线），消息会积压。
                    """, orderMessage.getOrderId(), orderType.getDefaultTag());
        } catch (Exception e) {
            log.error("❌ 发送带 Tag 的 Level8 消息失败", e);
            return "❌ 发送失败: " + e.getMessage();
        }
    }

    /**
     * 入口 3：发送带属性的订单消息，用于 SQL 过滤场景。
     */
    @GetMapping("/sendOrderWithProps")
    public String sendOrderWithProps(@RequestParam(defaultValue = "beijing") String region,
                                     @RequestParam(defaultValue = "100") BigDecimal amount,
                                     @RequestParam(defaultValue = "normal") String type) {
        Level8OrderType orderType = Level8OrderType.fromRequest(type);
        String orderId = resolveOrderId(null, orderType);

        Level8OrderMessage orderMessage = Level8OrderMessage.of(
                orderType,
                orderId,
                amount,
                region,
                randomUserId(),
                resolveUserLevel(orderType)
        );

        try {
            Message message = ClientServiceProvider.loadService()
                    .newMessageBuilder()
                    .setTopic(Level8Constants.ORDER_TOPIC)
                    .setTag(orderType.getDefaultTag())
                    .setKeys(orderMessage.getOrderId())
                    .addProperty("region", region)
                    .addProperty("amount", amount.toPlainString())
                    .setBody(objectMapper.writeValueAsBytes(orderMessage))
                    .build();

            SendReceipt receipt = producer.send(message);
            log.info("📤 [Buggy] 发送带属性订单 - OrderId={}, Region={}, Amount={}, MessageId={}",
                    orderMessage.getOrderId(), region, amount, receipt.getMessageId());

            return String.format("""
                    ✅ [Buggy] 带属性的订单已发送
                    - OrderId: %s
                    - Region: %s
                    - Amount: %s
                    ⚠️ Consumer SQL 表达式写成 region = beijing（缺少引号），会导致启动失败或过滤失效。
                    """, orderMessage.getOrderId(), region, amount);
        } catch (Exception e) {
            log.error("❌ 发送带属性 Level8 消息失败", e);
            return "❌ 发送失败: " + e.getMessage();
        }
    }

    /**
     * 批量发送随机订单，用于性能测试。
     */
    @GetMapping("/batchSend")
    public String batchSend(@RequestParam(defaultValue = "200") int count) {
        count = Math.min(Math.max(count, 1), 5000);
        int success = 0;
        for (int i = 0; i < count; i++) {
            Level8OrderType orderType = Level8OrderType.values()[random.nextInt(Level8OrderType.values().length)];
            Level8OrderMessage message = Level8OrderMessage.of(
                    orderType,
                    resolveOrderId(null, orderType),
                    randomAmount(orderType),
                    randomRegion(),
                    randomUserId(),
                    resolveUserLevel(orderType)
            );
            try {
                Message mqMessage = ClientServiceProvider.loadService()
                        .newMessageBuilder()
                        .setTopic(Level8Constants.ORDER_TOPIC)
                        .setKeys(message.getOrderId())
                        // Bug：批量场景也没设置 Tag
                        .setBody(objectMapper.writeValueAsBytes(message))
                        .build();
                producer.send(mqMessage);
                success++;
            } catch (Exception ex) {
                log.warn("批量发送失败 - {}", ex.getMessage());
            }
        }
        return String.format("""
                ✅ 批量发送完成
                - 请求条数: %d
                - 实际成功: %d
                - Bug: 所有消息没有 Tag，消费者端无法做高性能过滤。
                """, count, success);
    }

    /**
     * 查询所有消费者的消费统计。
     */
    @GetMapping("/checkConsumerStats")
    public String checkConsumerStats() {
        StringBuilder builder = new StringBuilder();
        builder.append("📊 Level 8 消费者统计（Buggy）\n");
        if (statsService.all().isEmpty()) {
            builder.append("暂无消费记录，可以先调用 sendOrder 接口。\n");
        } else {
            statsService.all().forEach(stats -> {
                builder.append(stats.formatDetail());
                builder.append("\n");
            });
        }
        builder.append("""
                🔍 建议演练：
                - 发送 4 种订单，观察所有消费者都收到了所有订单
                - 调用 buggy/sendOrder，再查看秒杀消费者统计
                """);
        return builder.toString();
    }

    /**
     * 查询指定消费者的统计。
     */
    @GetMapping("/checkConsumer")
    public String checkConsumer(@RequestParam String name) {
        Level8ConsumerStats stats = statsService.find(name);
        if (stats == null) {
            return "未找到消费者统计：" + name;
        }
        return stats.formatDetail();
    }

    /**
     * 用纯 Java 代码模拟 Tag、SQL 与消费者端过滤性能的差异。
     */
    @GetMapping("/compareFilterPerformance")
    public String compareFilterPerformance() {
        List<Level8OrderType> types = List.of(Level8OrderType.values());
        int sampleSize = 20_000;

        long startTag = System.currentTimeMillis();
        long tagMatches = sampleSize(types, sampleSize, this::tagFilter);
        long tagDuration = System.currentTimeMillis() - startTag;

        long startSql = System.currentTimeMillis();
        long sqlMatches = sampleSize(types, sampleSize, this::sqlFilter);
        long sqlDuration = System.currentTimeMillis() - startSql;

        long startCode = System.currentTimeMillis();
        long codeMatches = sampleSize(types, sampleSize, this::javaFilter);
        long codeDuration = System.currentTimeMillis() - startCode;

        return String.format(Locale.CHINA, """
                🧪 过滤性能对比（伪模拟，越低越好）
                - Tag 过滤    : %d ms (%d 条匹配)
                - SQL92 过滤 : %d ms (%d 条匹配)
                - Java 过滤  : %d ms (%d 条匹配)

                ⚠️ Bug: Buggy 版本在 Broker 端没有任何 Tag 过滤，
                还配置了复杂的 SQL 过滤表达式，性能最差，甚至回落到消费者端手动过滤。
                """, tagDuration, tagMatches, sqlDuration, sqlMatches, codeDuration, codeMatches);
    }

    private long sampleSize(List<Level8OrderType> types,
                            int size,
                            Predicate<Level8OrderType> predicate) {
        long matches = 0;
        for (int i = 0; i < size; i++) {
            Level8OrderType type = types.get(random.nextInt(types.size()));
            if (predicate.test(type)) {
                matches++;
            }
        }
        return matches;
    }

    private boolean tagFilter(Level8OrderType type) {
        return type == Level8OrderType.SECKILL;
    }

    private boolean sqlFilter(Level8OrderType type) {
        // 模拟复杂 SQL 条件，增加 CPU 消耗
        String expression = "(region IN ('beijing','shanghai','guangzhou','shenzhen') " +
                "AND amount > 100 AND userLevel = 'VIP') OR (orderType = 'VIP')";
        int hash = (type.getDefaultTag() + expression).hashCode();
        return (hash & 1) == 0;
    }

    private boolean javaFilter(Level8OrderType type) {
        // 模拟消费者端调用外部服务判断
        try {
            Thread.sleep(0, 10_000); // 10 微秒
        } catch (InterruptedException ignored) {
        }
        return type == Level8OrderType.VIP;
    }

    private String resolveOrderId(String provided, Level8OrderType type) {
        if (StringUtils.hasText(provided)) {
            return provided;
        }
        return type.name() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private BigDecimal randomAmount(Level8OrderType type) {
        int base = switch (type) {
            case NORMAL -> 50;
            case SECKILL -> 30;
            case PRESALE -> 200;
            case VIP -> 500;
        };
        return BigDecimal.valueOf(base + random.nextInt(200));
    }

    private String randomRegion() {
        String[] regions = {"beijing", "shanghai", "guangzhou", "shenzhen"};
        return regions[random.nextInt(regions.length)];
    }

    private String randomUserId() {
        return "USER-" + (100 + random.nextInt(900));
    }

    private String resolveUserLevel(Level8OrderType type) {
        return switch (type) {
            case VIP -> "VIP";
            case SECKILL -> "PLUS";
            case PRESALE -> "MEMBER";
            default -> "NORMAL";
        };
    }

    /**
     * 快速查看可用接口。
     */
    @GetMapping("/help")
    public String help() {
        return """
                🆘 Level8 Buggy 版本说明
                1. Tag 路由 Bug：
                   curl "http://localhost:8070/challenge/level8/sendOrder?type=normal&orderId=ORDER-001"
                   curl "http://localhost:8070/challenge/level8/sendOrder?type=seckill&orderId=ORDER-002"
                   curl "http://localhost:8070/challenge/level8/checkConsumerStats"

                2. Tag 写错 Bug：
                   curl "http://localhost:8070/challenge/level8/buggy/sendOrder?type=seckill&orderId=ORDER-003"
                   curl "http://localhost:8070/challenge/level8/checkConsumer?name=strict-seckill-consumer"

                3. SQL 过滤 Bug：
                   curl "http://localhost:8070/challenge/level8/sendOrderWithProps?region=beijing&amount=150"
                   curl "http://localhost:8070/challenge/level8/checkConsumer?name=beijing-consumer"

                4. 性能测试：
                   curl "http://localhost:8070/challenge/level8/batchSend?count=1000"
                   curl "http://localhost:8070/challenge/level8/compareFilterPerformance"
                """;
    }
}
