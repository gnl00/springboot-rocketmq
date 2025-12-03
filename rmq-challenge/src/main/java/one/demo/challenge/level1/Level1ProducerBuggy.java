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

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Level 1 挑战：资源泄漏问题
 *
 * 问题现象：
 * 1. 应用运行一段时间后内存持续增长
 * 2. 文件句柄数量不断增加
 * 3. 最终可能导致 OOM 或无法创建新线程
 *
 * 任务：
 * 1. 找出代码中的资源泄漏问题
 * 2. 分析为什么会导致资源泄漏
 * 3. 提出并实现解决方案
 *
 * 提示：
 * - 思考每次发送消息时都在做什么
 * - 考虑什么资源需要被释放
 * - 查看 RocketMQ Producer 的生命周期管理
 */
@Slf4j
@RestController
@RequestMapping("/challenge/level1")
public class Level1ProducerBuggy {

    private static final String ENDPOINTS = "localhost:8080";

    /**
     * 发送消息的接口
     *
     * Bug提示：这个方法每次调用都会发生什么？
     */
    @GetMapping("/send")
    public String sendMessageBad(@RequestParam(defaultValue = "demo") String topic,
                                 @RequestParam(defaultValue = "Hello RocketMQ") String message) {
        try {
            // 创建 Producer 实例
            ClientServiceProvider provider = ClientServiceProvider.loadService();
            ClientConfiguration configuration = ClientConfiguration.newBuilder()
                    .setEndpoints(ENDPOINTS)
                    .build();

            // 每次请求都创建新的 Producer
            Producer producer = provider.newProducerBuilder()
                    .setClientConfiguration(configuration)
                    .setTopics(topic)
                    .build();

            // 构建消息
            Message msg = provider.newMessageBuilder()
                    .setTopic(topic)
                    .setTag("level1")
                    .setKeys("key-" + System.currentTimeMillis())
                    .setBody(message.getBytes(StandardCharsets.UTF_8))
                    .build();

            // 发送消息
            SendReceipt receipt = producer.send(msg);
            log.info("消息发送成功，MessageId: {}", receipt.getMessageId());

            return "发送成功，MessageId: " + receipt.getMessageId();

        } catch (ClientException e) {
            log.error("发送消息失败", e);
            return "发送失败: " + e.getMessage();
        }
    }

    @GetMapping("/sendGood")
    public String sendMessageGood(@RequestParam(defaultValue = "demo") String topic,
                                 @RequestParam(defaultValue = "Hello RocketMQ") String message) {
        Producer producer = null;
        try {
            // 创建 Producer 实例
            ClientServiceProvider provider = ClientServiceProvider.loadService();
            ClientConfiguration configuration = ClientConfiguration.newBuilder()
                    .setEndpoints(ENDPOINTS)
                    .build();

            // 每次请求都创建新的 Producer
            producer = provider.newProducerBuilder()
                    .setClientConfiguration(configuration)
                    .setTopics(topic)
                    .build();

            // 构建消息
            Message msg = provider.newMessageBuilder()
                    .setTopic(topic)
                    .setTag("level1")
                    .setKeys("key-" + System.currentTimeMillis())
                    .setBody(message.getBytes(StandardCharsets.UTF_8))
                    .build();

            // 发送消息
            SendReceipt receipt = producer.send(msg);
            log.info("消息发送成功，MessageId: {}", receipt.getMessageId());

            return "发送成功，MessageId: " + receipt.getMessageId();

        } catch (ClientException e) {
            log.error("发送消息失败", e);
            return "发送失败: " + e.getMessage();
        } finally {
            if (producer != null) {
                try {
                    producer.close();
                    log.info("producer closed");
                } catch (IOException e) {
                    log.error("producer close failed, e=", e);
                }
            }
        }
    }

    /**
     * 模拟批量发送消息的场景
     *
     * Bug提示：如果快速调用这个接口会发生什么？
     */
    @GetMapping("/batchSend")
    public String batchSendBad(@RequestParam(defaultValue = "demo") String topic,
                                @RequestParam(defaultValue = "10") int count) {
        int successCount = 0;
        int failCount = 0;

        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(ENDPOINTS)
                .build();

        for (int i = 0; i < count; i++) {
            try {
                Producer producer = provider.newProducerBuilder()
                        .setClientConfiguration(configuration)
                        .setTopics(topic)
                        .build();

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

        return String.format("批量发送完成，成功: %d，失败: %d", successCount, failCount);
    }

    @GetMapping("/batchSendGood")
    public String batchSendGood(@RequestParam(defaultValue = "demo") String topic,
                           @RequestParam(defaultValue = "10") int count) {
        int successCount = 0;
        int failCount = 0;

        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(ENDPOINTS)
                .build();

        Producer producer = null;
        try {
            producer = provider.newProducerBuilder()
                    .setClientConfiguration(configuration)
                    .setTopics(topic)
                    .build();

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
        } catch (ClientException e) {
            log.error("批量发送消息失败", e);
        } finally {
            if (producer != null) {
                try {
                    producer.close();
                    log.info("producer closed");
                } catch (IOException e) {
                    log.error("producer close failed, e=", e);
                }
            }
        }

        return String.format("批量发送完成，成功: %d，失败: %d", successCount, failCount);
    }

    /**
     * 健康检查接口 - 可以用来观察系统状态
     */
    @GetMapping("/health")
    public String health() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        // 获取线程数 - 这是发现资源泄漏的关键指标！
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
