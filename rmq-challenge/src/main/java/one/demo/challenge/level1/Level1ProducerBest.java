package one.demo.challenge.level1;

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

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Level 1 最佳实践：单例 Producer
 *
 * 为什么这是生产环境的最佳实践？
 * 1. Producer 是线程安全的，可以被多个线程共享
 * 2. 避免频繁创建和销毁 Producer 的开销
 * 3. 复用底层的网络连接和线程池
 * 4. 性能更好，资源占用更少
 *
 * 对比：
 * - 每次请求创建 Producer：线程数暴涨到 7000+
 * - try-with-resources：线程数峰值后回落，但仍有频繁创建销毁的开销
 * - 单例 Producer：线程数稳定在较低水平（约 30-50）
 */
@Slf4j
@RestController
@RequestMapping("/challenge/level1/best")
public class Level1ProducerBest {

    private static final String ENDPOINTS = "localhost:8080";

    // 单例 Producer，应用启动时创建，应用关闭时销毁
    private Producer producer;

    /**
     * 应用启动时初始化 Producer
     */
    @PostConstruct
    public void init() throws ClientException {
        log.info("初始化 RocketMQ Producer...");

        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(ENDPOINTS)
                .build();

        this.producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .build();

        log.info("RocketMQ Producer 初始化完成");
    }

    /**
     * 应用关闭时销毁 Producer
     */
    @PreDestroy
    public void destroy() {
        if (producer != null) {
            try {
                producer.close();
                log.info("RocketMQ Producer 已关闭");
            } catch (IOException e) {
                log.error("关闭 RocketMQ Producer 失败", e);
            }
        }
    }

    /**
     * 发送消息：复用单例 Producer
     */
    @GetMapping("/send")
    public String sendMessage(@RequestParam(defaultValue = "demo") String topic,
                              @RequestParam(defaultValue = "Hello RocketMQ") String message) {
        try {
            ClientServiceProvider provider = ClientServiceProvider.loadService();
            Message msg = provider.newMessageBuilder()
                    .setTopic(topic)
                    .setTag("best")
                    .setKeys("key-" + System.currentTimeMillis())
                    .setBody(message.getBytes(StandardCharsets.UTF_8))
                    .build();

            // 直接使用单例 Producer，无需创建和关闭
            SendReceipt receipt = producer.send(msg);
            log.info("消息发送成功，MessageId: {}", receipt.getMessageId());

            return "发送成功，MessageId: " + receipt.getMessageId();

        } catch (ClientException e) {
            log.error("发送消息失败", e);
            return "发送失败: " + e.getMessage();
        }
    }

    /**
     * 批量发送：复用单例 Producer
     */
    @GetMapping("/batchSend")
    public String batchSend(@RequestParam(defaultValue = "demo") String topic,
                           @RequestParam(defaultValue = "10") int count) {
        int successCount = 0;
        int failCount = 0;

        ClientServiceProvider provider = ClientServiceProvider.loadService();

        for (int i = 0; i < count; i++) {
            try {
                Message message = provider.newMessageBuilder()
                        .setTopic(topic)
                        .setTag("batch-best")
                        .setKeys("batch-" + i)
                        .setBody(("消息内容-" + i).getBytes(StandardCharsets.UTF_8))
                        .build();

                // 直接使用单例 Producer
                producer.send(message);
                successCount++;
                log.info("批量发送第 {} 条消息成功", i + 1);

            } catch (ClientException e) {
                failCount++;
                log.error("批量发送第 {} 条消息失败", i + 1, e);
            }
        }

        return String.format("批量发送完成，成功: %d，失败: %d", successCount, failCount);
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
            "系统状态 - 内存(总/已用/空闲): %d/%d/%d MB | 活跃线程数: %d | Producer状态: %s",
            totalMemory / 1024 / 1024,
            usedMemory / 1024 / 1024,
            freeMemory / 1024 / 1024,
            threadCount,
            producer != null ? "已初始化" : "未初始化"
        );
    }
}