package one.demo.challenge.level2;

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

/**
 * Level 2 挑战：消息发送失败与重试机制
 *
 * 问题现象：
 * 1. 当 Broker 网络抖动时，API 接口超时（响应时间从 50ms → 30s）
 * 2. 应用日志显示大量重试消息，CPU 飙升
 * 3. 部分消息发送失败后彻底丢失
 * 4. 并发情况下，大量请求被阻塞
 *
 * 任务：
 * 1. 找出代码中关于重试机制的问题
 * 2. 分析为什么会导致接口超时和 CPU 飙升
 * 3. 提出并实现合理的重试和失败处理方案
 *
 * 提示：
 * - 观察重试的逻辑：次数、间隔、退避策略
 * - 思考同步发送 vs 异步发送的区别
 * - 考虑失败消息的兜底方案
 * - 注意超时时间的设置
 */
@Slf4j
@RestController
@RequestMapping("/challenge/level2")
public class Level2ProducerBuggy {

    private static final String ENDPOINTS = "localhost:8080";
    private Producer producer;

    @PostConstruct
    public void init() throws ClientException {
        log.info("初始化 RocketMQ Producer...");

        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(ENDPOINTS)
                // Bug 1: 超时时间设置过长
                .setRequestTimeout(Duration.ofSeconds(30))
                .build();

        this.producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                // Bug 2: 没有设置合理的重试次数
                .setMaxAttempts(10)  // 默认重试 10 次！
                .build();

        log.info("RocketMQ Producer 初始化完成");
    }

    /**
     * 发送消息：存在多个问题
     *
     * Bug 提示：
     * 1. 如果发送失败会怎样？
     * 2. 用户需要等多久才能得到响应？
     * 3. 失败的消息去哪了？
     */
    @GetMapping("/send")
    public String sendMessage(@RequestParam(defaultValue = "demo") String topic,
                              @RequestParam(defaultValue = "Hello RocketMQ") String message) {
        long startTime = System.currentTimeMillis();

        try {
            ClientServiceProvider provider = ClientServiceProvider.loadService();
            Message msg = provider.newMessageBuilder()
                    .setTopic(topic)
                    .setTag("level2")
                    .setKeys("key-" + System.currentTimeMillis())
                    .setBody(message.getBytes(StandardCharsets.UTF_8))
                    .build();

            // Bug 3: 同步发送，阻塞主线程
            // 如果 Broker 慢或网络抖动，这里会阻塞很久（30s * 10次重试 = 最长 5 分钟！）
            SendReceipt receipt = producer.send(msg);
            long cost = System.currentTimeMillis() - startTime;
            log.info("消息发送成功，MessageId: {}, 耗时: {}ms", receipt.getMessageId(), cost);

            return String.format("发送完成 currTime: %dms", System.currentTimeMillis());

        } catch (ClientException e) {
            long cost = System.currentTimeMillis() - startTime;
            log.error("消息发送失败，耗时: {}ms, 错误: {}", cost, e.getMessage());

            // Bug 4: 发送失败后，消息就丢失了，没有任何兜底措施
            return String.format("发送失败: %s, 耗时: %dms", e.getMessage(), cost);
        }
    }

    /**
     * 自定义重试逻辑：更糟糕的实现
     *
     * Bug 提示：这个重试逻辑有什么问题？
     */
    @GetMapping("/sendWithRetry")
    public String sendWithRetry(@RequestParam(defaultValue = "demo") String topic,
                                @RequestParam(defaultValue = "Hello RocketMQ") String message) {
        long startTime = System.currentTimeMillis();
        int retryCount = 0;
        int maxRetries = 5;

        ClientServiceProvider provider = ClientServiceProvider.loadService();
        Message msg = provider.newMessageBuilder()
                .setTopic(topic)
                .setTag("retry")
                .setKeys("retry-" + System.currentTimeMillis())
                .setBody(message.getBytes(StandardCharsets.UTF_8))
                .build();

        // Bug 5: 自己实现的重试逻辑有严重问题
        while (retryCount < maxRetries) {
            try {
                SendReceipt receipt = producer.send(msg);
                long cost = System.currentTimeMillis() - startTime;
                log.info("消息发送成功（重试 {} 次），MessageId: {}, 耗时: {}ms",
                    retryCount, receipt.getMessageId(), cost);
                return String.format("发送成功（重试 %d 次），耗时: %dms", retryCount, cost);

            } catch (ClientException e) {
                retryCount++;
                log.warn("第 {} 次发送失败: {}", retryCount, e.getMessage());

                // Bug 6: 没有重试间隔，立即重试会加剧服务压力
                // Bug 7: 没有指数退避策略
                // Bug 8: 同步阻塞，用户一直在等待

                if (retryCount >= maxRetries) {
                    long cost = System.currentTimeMillis() - startTime;
                    log.error("消息发送失败，已重试 {} 次，总耗时: {}ms", retryCount, cost);

                    // Bug 9: 重试失败后，消息仍然丢失
                    return String.format("发送失败（已重试 %d 次），耗时: %dms", retryCount, cost);
                }
            }
        }

        return "发送失败";
    }

    /**
     * 批量发送：问题会被放大
     *
     * Bug 提示：如果批量发送中有部分消息失败会怎样？
     */
    @GetMapping("/batchSend")
    public String batchSend(@RequestParam(defaultValue = "demo") String topic,
                           @RequestParam(defaultValue = "10") int count) {
        long startTime = System.currentTimeMillis();
        int successCount = 0;
        int failCount = 0;

        ClientServiceProvider provider = ClientServiceProvider.loadService();

        // Bug 10: 串行发送，一个失败会阻塞后续所有消息
        for (int i = 0; i < count; i++) {
            try {
                Message message = provider.newMessageBuilder()
                        .setTopic(topic)
                        .setTag("batch")
                        .setKeys("batch-" + i)
                        .setBody(("消息-" + i).getBytes(StandardCharsets.UTF_8))
                        .build();

                // 如果第一条消息就失败并重试多次，后面的消息都要等待
                producer.send(message);
                successCount++;

            } catch (ClientException e) {
                failCount++;
                log.error("批量发送第 {} 条消息失败: {}", i + 1, e.getMessage());
                // Bug 11: 失败的消息没有记录下来，无法后续补偿
            }
        }

        long cost = System.currentTimeMillis() - startTime;
        return String.format("批量发送完成，成功: %d，失败: %d，总耗时: %dms",
            successCount, failCount, cost);
    }

    /**
     * 模拟 Broker 故障的测试接口
     */
    @GetMapping("/sendToInvalidBroker")
    public String sendToInvalidBroker(@RequestParam(defaultValue = "测试消息") String message) {
        long startTime = System.currentTimeMillis();

        try {
            // 创建一个指向无效 Broker 的 Producer
            ClientServiceProvider provider = ClientServiceProvider.loadService();
            ClientConfiguration configuration = ClientConfiguration.newBuilder()
                    .setEndpoints("localhost:9999")  // 不存在的 Broker
                    .setRequestTimeout(Duration.ofSeconds(5))
                    .build();

            try (Producer badProducer = provider.newProducerBuilder()
                    .setClientConfiguration(configuration)
                    .setMaxAttempts(3)
                    .build()) {

                Message msg = provider.newMessageBuilder()
                        .setTopic("demo")
                        .setTag("test")
                        .setKeys("test-" + System.currentTimeMillis())
                        .setBody(message.getBytes(StandardCharsets.UTF_8))
                        .build();

                // 这里会尝试连接失败的 Broker，触发重试
                SendReceipt receipt = badProducer.send(msg);

                long cost = System.currentTimeMillis() - startTime;
                return String.format("发送成功，耗时: %dms", cost);
            }

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - startTime;
            log.error("发送到无效 Broker 失败，耗时: {}ms", cost, e);
            return String.format("发送失败: %s, 耗时: %dms（观察这个耗时！）", e.getMessage(), cost);
        }
    }

    @GetMapping("/health")
    public String health() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
        ThreadGroup parentGroup;
        while ((parentGroup = rootGroup.getParent()) != null) {
            rootGroup = parentGroup;
        }
        int threadCount = rootGroup.activeCount();

        return String.format(
            "系统状态 - 内存: %d/%d/%d MB | 线程数: %d",
            totalMemory / 1024 / 1024,
            usedMemory / 1024 / 1024,
            freeMemory / 1024 / 1024,
            threadCount
        );
    }
}