package one.demo.challenge.level4;

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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Level 4 生产者：快速生产大量消息，制造消息积压场景
 */
@Slf4j
@RestController
@RequestMapping("/challenge/level4")
public class Level4Producer {

    private static final String ENDPOINTS = "localhost:8080";
    private static final String TOPIC = "order-notification";

    private Producer producer;
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

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

        log.info("Level 4 Producer 初始化完成");
    }

    /**
     * 快速发送消息（制造积压场景）
     *
     * @param count 发送消息数量
     * @param ratePerSecond 每秒发送速率（0 表示不限速）
     */
    @GetMapping("/produceMessages")
    public String produceMessages(@RequestParam(defaultValue = "1000") int count,
                                  @RequestParam(defaultValue = "100") int ratePerSecond) {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        log.info("🚀 开始生产消息 - 总数: {}, 速率: {} msg/s", count, ratePerSecond);

        // 计算每批次发送间隔
        long batchInterval = ratePerSecond > 0 ? 1000 / ratePerSecond : 0;

        executorService.submit(() -> {
            ClientServiceProvider provider = ClientServiceProvider.loadService();

            for (int i = 0; i < count; i++) {
                try {
                    String messageBody = String.format("订单通知消息-%d，时间: %d",
                            i, System.currentTimeMillis());

                    Message message = provider.newMessageBuilder()
                            .setTopic(TOPIC)
                            .setTag("order-created")
                            .setKeys("order-" + i)
                            .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
                            .build();

                    // 异步发送，提高吞吐量
                    CompletableFuture<SendReceipt> future = producer.sendAsync(message);

                    future.whenComplete((receipt, throwable) -> {
                        if (throwable != null) {
                            failCount.incrementAndGet();
                        } else {
                            int current = successCount.incrementAndGet();
                            if (current % 100 == 0) {
                                long elapsed = System.currentTimeMillis() - startTime;
                                double rate = current * 1000.0 / elapsed;
                                log.info(String.format("📤 已发送: %d 条, 速率: %.2f msg/s", current, rate));
                            }
                        }
                    });

                    // 限速
                    if (batchInterval > 0) {
                        TimeUnit.MILLISECONDS.sleep(batchInterval);
                    }

                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.error("发送消息失败 - Index: {}", i, e);
                }
            }

            long totalTime = System.currentTimeMillis() - startTime;
            double avgRate = successCount.get() * 1000.0 / totalTime;

            log.info(String.format("✅ 消息发送完成 - 成功: %d, 失败: %d, 总耗时: %d ms, 平均速率: %.2f msg/s",
                    successCount.get(), failCount.get(), totalTime, avgRate));
        });

        return String.format("✅ 已开始发送消息 - 总数: %d, 目标速率: %d msg/s",
                count, ratePerSecond);
    }

    /**
     * 持续发送消息（模拟生产环境持续压力）
     */
    @GetMapping("/continuousProduce")
    public String continuousProduce(@RequestParam(defaultValue = "100") int ratePerSecond,
                                   @RequestParam(defaultValue = "60") int durationSeconds) {

        log.info("🔄 开始持续生产消息 - 速率: {} msg/s, 持续时间: {} 秒",
                ratePerSecond, durationSeconds);

        AtomicLong totalSent = new AtomicLong(0);
        AtomicLong startTime = new AtomicLong(System.currentTimeMillis());

        executorService.submit(() -> {
            ClientServiceProvider provider = ClientServiceProvider.loadService();
            long endTime = System.currentTimeMillis() + (durationSeconds * 1000L);
            long intervalMs = 1000 / ratePerSecond;

            while (System.currentTimeMillis() < endTime) {
                try {
                    long current = totalSent.incrementAndGet();
                    String messageBody = String.format("持续消息-%d，时间: %d",
                            current, System.currentTimeMillis());

                    Message message = provider.newMessageBuilder()
                            .setTopic(TOPIC)
                            .setTag("continuous")
                            .setKeys("msg-" + current)
                            .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
                            .build();

                    producer.sendAsync(message);

                    if (current % 100 == 0) {
                        long elapsed = System.currentTimeMillis() - startTime.get();
                        double rate = current * 1000.0 / elapsed;
                        log.info(String.format("📤 持续发送中 - 已发送: %d 条, 当前速率: %.2f msg/s",
                                current, rate));
                    }

                    TimeUnit.MILLISECONDS.sleep(intervalMs);

                } catch (Exception e) {
                    log.error("持续发送失败", e);
                }
            }

            long totalTime = System.currentTimeMillis() - startTime.get();
            double avgRate = totalSent.get() * 1000.0 / totalTime;

            log.info(String.format("✅ 持续发送完成 - 总共发送: %d 条, 总耗时: %d ms, 平均速率: %.2f msg/s",
                    totalSent.get(), totalTime, avgRate));
        });

        return String.format("✅ 已开始持续发送 - 速率: %d msg/s, 持续: %d 秒",
                ratePerSecond, durationSeconds);
    }

    /**
     * 查询消费者积压情况（通过 RocketMQ 管理接口）
     *
     * 注意：这需要 RocketMQ 的管理 API，简化版本可以通过日志观察
     */
    @GetMapping("/checkBacklog")
    public String checkBacklog() {
        // 简化版本：返回提示信息
        return """
                💡 查看消息积压的方法：

                1. 使用 RocketMQ Dashboard:
                   http://localhost:8080 (如果部署了 Dashboard)

                2. 使用命令行工具:
                   mqadmin consumerProgress -g notification-consumer-buggy

                3. 观察日志中的消费速率:
                   - 生产速率: 100 msg/s
                   - 消费速率: 2 msg/s (1 线程 × 500ms/条)
                   - 积压速度: 98 msg/s

                4. 计算积压数量:
                   如果持续 60 秒 = 98 × 60 = 5880 条积压
                """;
    }

    /**
     * 停止所有发送任务
     */
    @GetMapping("/stop")
    public String stop() {
        executorService.shutdownNow();
        log.info("⏹️ 已停止所有发送任务");
        return "✅ 已停止所有发送任务";
    }
}
