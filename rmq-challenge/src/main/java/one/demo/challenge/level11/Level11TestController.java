package one.demo.challenge.level11;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Level 11 测试控制器
 * 提供轨迹查询和统计接口
 */
@RestController
@RequestMapping("/challenge/level11/buggy")
public class Level11TestController {

    @Autowired
    private Level11TraceService traceService;

    /**
     * 查看统计信息
     */
    @GetMapping("/stats")
    public String stats() {
        return traceService.getStats();
    }

    /**
     * 查询消息轨迹
     * Bug: 由于没有记录轨迹，查询不到任何信息
     */
    @GetMapping("/queryTrace")
    public String queryTrace(@RequestParam String traceId) {
        Level11MessageTrace trace = traceService.getTrace(traceId);

        if (trace == null) {
            return String.format("""
                    ❌ 未找到轨迹信息
                    - TraceId: %s

                    🔍 Bug 原因：
                    Producer 和 Consumer 都没有记录轨迹信息！

                    💡 提示：
                    需要在消息发送和消费的各个关键节点记录轨迹
                    """, traceId);
        }

        return formatTrace(trace);
    }

    /**
     * 根据订单 ID 查询轨迹
     */
    @GetMapping("/queryByOrderId")
    public String queryByOrderId(@RequestParam String orderId) {
        List<Level11MessageTrace> traces = traceService.getTracesByOrderId(orderId);

        if (traces.isEmpty()) {
            return String.format("""
                    ❌ 未找到订单相关的轨迹信息
                    - OrderId: %s

                    🔍 Bug 原因：
                    没有记录轨迹信息！
                    """, orderId);
        }

        StringBuilder result = new StringBuilder();
        result.append(String.format("📦 订单轨迹查询 - OrderId: %s\n", orderId));
        result.append(String.format("找到 %d 条轨迹记录\n\n", traces.size()));

        for (Level11MessageTrace trace : traces) {
            result.append(formatTrace(trace));
            result.append("\n---\n\n");
        }

        return result.toString();
    }

    /**
     * 查询慢消息
     * Bug: 由于没有记录性能指标，查询不到慢消息
     */
    @GetMapping("/slowMessages")
    public String slowMessages(@RequestParam(defaultValue = "1000") long threshold) {
        List<Level11MessageTrace> slowMessages = traceService.getSlowMessages(threshold);

        if (slowMessages.isEmpty()) {
            return String.format("""
                    ❌ 未找到慢消息
                    - 阈值: %d ms

                    🔍 Bug 原因：
                    没有记录消息处理的性能指标！

                    💡 提示：
                    需要记录：
                    1. 发送时间
                    2. Broker 接收时间
                    3. 消费开始时间
                    4. 消费结束时间
                    5. 计算各阶段延迟
                    """, threshold);
        }

        StringBuilder result = new StringBuilder();
        result.append(String.format("🐌 慢消息列表（阈值: %d ms）\n", threshold));
        result.append(String.format("找到 %d 条慢消息\n\n", slowMessages.size()));

        for (Level11MessageTrace trace : slowMessages) {
            result.append(String.format("- OrderId: %s, TotalLatency: %d ms, TraceId: %s\n",
                    trace.getOrderId(), trace.getTotalLatency(), trace.getTraceId()));
        }

        return result.toString();
    }

    /**
     * 查询失败消息
     * Bug: 由于没有记录错误信息，查询不到失败详情
     */
    @GetMapping("/failedMessages")
    public String failedMessages() {
        List<Level11MessageTrace> failedMessages = traceService.getFailedMessages();

        if (failedMessages.isEmpty()) {
            return """
                    ❌ 未找到失败消息

                    🔍 Bug 原因：
                    没有记录消息失败的详细信息！

                    💡 提示：
                    需要记录：
                    1. 失败原因
                    2. 错误堆栈
                    3. 重试次数
                    4. 失败时间
                    """;
        }

        StringBuilder result = new StringBuilder();
        result.append(String.format("❌ 失败消息列表\n找到 %d 条失败消息\n\n", failedMessages.size()));

        for (Level11MessageTrace trace : failedMessages) {
            result.append(String.format("""
                    - OrderId: %s
                      TraceId: %s
                      ErrorMessage: %s
                      RetryTimes: %d

                    """, trace.getOrderId(), trace.getTraceId(),
                    trace.getErrorMessage(), trace.getRetryTimes()));
        }

        return result.toString();
    }

    /**
     * 重置统计
     */
    @GetMapping("/reset")
    public String reset() {
        traceService.reset();
        return "✅ 统计已重置";
    }

    /**
     * 格式化轨迹信息
     */
    private String formatTrace(Level11MessageTrace trace) {
        return String.format("""
                📊 消息轨迹详情
                - TraceId: %s
                - MessageId: %s
                - OrderId: %s

                ⏱️ 时间线：
                - 发送时间: %s
                - Broker接收: %s
                - 消费开始: %s
                - 消费结束: %s

                📈 性能指标：
                - Broker延迟: %s ms
                - 消费者延迟: %s ms
                - 处理耗时: %s ms
                - 总延迟: %s ms

                📝 处理结果：
                - 结果: %s
                - 错误信息: %s
                - 重试次数: %d
                """,
                trace.getTraceId(),
                trace.getMessageId(),
                trace.getOrderId(),
                trace.getSendTime(),
                trace.getBrokerReceiveTime(),
                trace.getConsumeStartTime(),
                trace.getConsumeEndTime(),
                trace.getBrokerLatency(),
                trace.getConsumerLatency(),
                trace.getProcessingTime(),
                trace.getTotalLatency(),
                trace.getConsumeResult(),
                trace.getErrorMessage(),
                trace.getRetryTimes());
    }
}
