package one.demo.challenge.level1;

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

/**
 * Level 1 修复版本：使用 try-with-resources
 *
 * 优势：
 * 1. 代码更简洁
 * 2. 自动关闭资源，无需手动 close
 * 3. 异常处理更安全（避免 finally 中的异常覆盖主异常）
 */
@Slf4j
@RestController
@RequestMapping("/challenge/level1/fixed")
public class Level1ProducerFixed {

    private static final String ENDPOINTS = "localhost:8080";

    /**
     * 方案1：使用 try-with-resources（最推荐）
     */
    @GetMapping("/send")
    public String sendMessage(@RequestParam(defaultValue = "demo") String topic,
                              @RequestParam(defaultValue = "Hello RocketMQ") String message) {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(ENDPOINTS)
                .build();

        // try-with-resources 会自动调用 producer.close()
        try (Producer producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics(topic)
                .build()) {

            Message msg = provider.newMessageBuilder()
                    .setTopic(topic)
                    .setTag("fixed")
                    .setKeys("key-" + System.currentTimeMillis())
                    .setBody(message.getBytes(StandardCharsets.UTF_8))
                    .build();

            SendReceipt receipt = producer.send(msg);
            log.info("消息发送成功，MessageId: {}", receipt.getMessageId());

            return "发送成功，MessageId: " + receipt.getMessageId();

        } catch (Exception e) {
            log.error("发送消息失败", e);
            return "发送失败: " + e.getMessage();
        }
        // Producer 会在这里自动关闭，无论是否发生异常
    }

    /**
     * 批量发送：复用 Producer
     */
    @GetMapping("/batchSend")
    public String batchSend(@RequestParam(defaultValue = "demo") String topic,
                           @RequestParam(defaultValue = "10") int count) {
        int successCount = 0;
        int failCount = 0;

        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(ENDPOINTS)
                .build();

        // 只创建一次 Producer，发送所有消息
        try (Producer producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics(topic)
                .build()) {

            for (int i = 0; i < count; i++) {
                try {
                    Message message = provider.newMessageBuilder()
                            .setTopic(topic)
                            .setTag("batch")
                            .setKeys("batch-" + i)
                            .setBody(("消息内容-" + i).getBytes(StandardCharsets.UTF_8))
                            .build();

                    producer.send(message);
                    successCount++;
                    log.info("批量发送第 {} 条消息成功", i + 1);

                } catch (ClientException e) {
                    failCount++;
                    log.error("批量发送第 {} 条消息失败", i + 1, e);
                }
            }

        } catch (Exception e) {
            log.error("创建 Producer 失败", e);
            return "批量发送失败: " + e.getMessage();
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
            "系统状态 - 内存(总/已用/空闲): %d/%d/%d MB | 活跃线程数: %d",
            totalMemory / 1024 / 1024,
            usedMemory / 1024 / 1024,
            freeMemory / 1024 / 1024,
            threadCount
        );
    }
}
