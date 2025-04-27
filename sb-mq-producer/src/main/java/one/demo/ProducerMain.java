package one.demo;

import com.sun.management.OperatingSystemMXBean;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
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
    private RocketMQTemplate rocketMQTemplate;

    @GetMapping("/prod")
    public void prod(@RequestParam String topic, @RequestParam String message) {
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
            rocketMQTemplate.convertAndSend(topic, messageStr);
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
