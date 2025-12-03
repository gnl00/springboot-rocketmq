package one.demo.challenge.level2;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Level 2 测试工具：用于模拟并发请求和压力测试
 *
 * 这个工具可以帮助你：
 * 1. 模拟并发发送场景
 * 2. 观察接口响应时间
 * 3. 测试在故障情况下的表现
 */
@Slf4j
@RestController
@RequestMapping("/challenge/level2/test")
public class Level2TestTool {

    private final ExecutorService executorService = Executors.newFixedThreadPool(20);

    /**
     * 并发测试工具
     *
     * 用法：
     * curl "http://localhost:8070/challenge/level2/test/concurrent?threads=10&messagesPerThread=5"
     */
    @GetMapping("/concurrent")
    public String concurrentTest(@RequestParam(defaultValue = "10") int threads,
                                  @RequestParam(defaultValue = "5") int messagesPerThread) {
        long startTime = System.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        log.info("开始并发测试：{} 个线程，每个线程发送 {} 条消息", threads, messagesPerThread);

        for (int i = 0; i < threads; i++) {
            final int threadId = i;
            executorService.submit(() -> {
                try {
                    for (int j = 0; j < messagesPerThread; j++) {
                        try {
                            // 调用有问题的发送接口
                            String message = String.format("Thread-%d-Msg-%d", threadId, j);
                            // 这里应该调用 Level2ProducerBuggy 的发送方法
                            // 为了简化，这里只是模拟
                            Thread.sleep(10); // 模拟发送耗时
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            failCount.incrementAndGet();
                            log.error("发送失败", e);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            log.error("等待测试完成时被中断", e);
        }

        long cost = System.currentTimeMillis() - startTime;

        return String.format(
            "并发测试完成 - 线程数: %d, 每线程消息数: %d, 成功: %d, 失败: %d, 总耗时: %dms, 平均耗时: %dms/msg",
            threads, messagesPerThread, successCount.get(), failCount.get(),
            cost, cost / (threads * messagesPerThread)
        );
    }

    /**
     * 压力测试：快速发送大量消息
     */
    @GetMapping("/stress")
    public String stressTest(@RequestParam(defaultValue = "100") int count) {
        log.warn("开始压力测试：快速发送 {} 条消息", count);

        long startTime = System.currentTimeMillis();
        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < count; i++) {
            try {
                // 模拟快速发送
                Thread.sleep(1);
                successCount++;
            } catch (Exception e) {
                failCount++;
            }
        }

        long cost = System.currentTimeMillis() - startTime;

        return String.format(
            "压力测试完成 - 消息数: %d, 成功: %d, 失败: %d, 总耗时: %dms, 平均耗时: %dms/msg",
            count, successCount, failCount, cost, cost / count
        );
    }

    /**
     * 获取当前系统负载
     */
    @GetMapping("/load")
    public String getLoad() {
        Runtime runtime = Runtime.getRuntime();
        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
        while (rootGroup.getParent() != null) {
            rootGroup = rootGroup.getParent();
        }

        return String.format(
            "系统负载 - 可用处理器: %d, 活跃线程: %d, 内存使用: %d MB / %d MB",
            runtime.availableProcessors(),
            rootGroup.activeCount(),
            (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024,
            runtime.totalMemory() / 1024 / 1024
        );
    }
}