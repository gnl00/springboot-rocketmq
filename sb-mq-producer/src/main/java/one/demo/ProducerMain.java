package one.demo;

import com.sun.management.OperatingSystemMXBean;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RestController
@SpringBootApplication
public class ProducerMain {
    public static void main(String[] args) {
        SpringApplication.run(ProducerMain.class, args);
    }

    private static final ScheduledExecutorService EXECUTOR = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() << 1);

    @Resource
    private Producer producer;

    @GetMapping("/prod")
    public void prod(@RequestParam String topic, @RequestParam String msg) {
        AtomicInteger msgCount = new AtomicInteger();
        EXECUTOR.scheduleAtFixedRate(() -> {

            // 获取 OperatingSystemMXBean 实例
            OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            // 获取系统负载（最近 1 分钟的平均负载）
            double systemLoadAverage = osBean.getSystemLoadAverage();
            // 获取 JVM 进程的 CPU 使用率
            double processCpuLoad = osBean.getProcessCpuLoad();
            // 获取系统整体的 CPU 使用率
            double systemCpuLoad = osBean.getSystemCpuLoad();

            String messageStr = String.format("CPULoadInfo$time=%s#SystemLoadAverage=%s#ProcessCPULoad=%s#SystemCPULoad=%s", System.currentTimeMillis(), systemLoadAverage, processCpuLoad, systemCpuLoad);

            // 普通消息发送。
            ClientServiceProvider provider = ClientServiceProvider.loadService();
            Message message = provider.newMessageBuilder()
                    .setTopic(topic)
                    // 设置消息索引键，可根据关键字精确查找某条消息。
                    .setKeys("messageKey")
                    // 设置消息Tag，用于消费端根据指定Tag过滤消息。
                    .setTag("messageTag")
                    // 消息体。
                    .setBody("messageBody".getBytes())
                    .build();
            try {
                // 发送消息，需要关注发送结果，并捕获失败等异常。
                SendReceipt sendReceipt = producer.send(message);
                log.info("Send message successfully, messageId={}", sendReceipt.getMessageId());
            } catch (ClientException e) {
                log.error("Failed to send message", e);
            }

            msgCount.getAndIncrement();
            log.info("send id={} to topic: {} | message: {}", msgCount.get(), topic, messageStr);
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void test() {
        System.getenv();
        // 获取 OperatingSystemMXBean 实例
        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        // 获取系统负载（最近 1 分钟的平均负载）
        double systemLoadAverage = osBean.getSystemLoadAverage();
        System.out.println("System Load Average (1 min): " + systemLoadAverage);

        // 获取 JVM 进程的 CPU 使用率
        double processCpuLoad = osBean.getProcessCpuLoad();
        System.out.println("Process CPU Load: " + processCpuLoad);

        // 获取系统整体的 CPU 使用率
        double systemCpuLoad = osBean.getSystemCpuLoad();
        System.out.println("System CPU Load: " + systemCpuLoad);
        System.out.println("test");
    }
}
