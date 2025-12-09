package one.demo.challenge.level11;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Level 11 消息轨迹服务
 * 负责记录和查询消息轨迹
 */
@Slf4j
@Service
public class Level11TraceService {

    // 存储所有消息轨迹
    private final Map<String, Level11MessageTrace> traces = new ConcurrentHashMap<>();

    // 统计信息
    private final AtomicLong totalMessages = new AtomicLong(0);
    private final AtomicLong successMessages = new AtomicLong(0);
    private final AtomicLong failureMessages = new AtomicLong(0);
    private final AtomicLong totalLatency = new AtomicLong(0);

    /**
     * 记录消息发送
     */
    public void recordSend(String traceId, String messageId, String orderId) {
        Level11MessageTrace trace = new Level11MessageTrace();
        trace.setTraceId(traceId);
        trace.setMessageId(messageId);
        trace.setOrderId(orderId);
        trace.setProducerHost("localhost");
        trace.setProducerApp("rmq-challenge");
        trace.setSendTime(LocalDateTime.now());
        trace.setRetryTimes(0);

        traces.put(traceId, trace);
        totalMessages.incrementAndGet();

        log.debug("📝 [Trace] 记录消息发送 - TraceId: {}, MessageId: {}", traceId, messageId);
    }

    /**
     * 记录 Broker 接收
     */
    public void recordBrokerReceive(String traceId) {
        Level11MessageTrace trace = traces.get(traceId);
        if (trace != null) {
            trace.setBrokerReceiveTime(LocalDateTime.now());

            // 计算 Broker 延迟
            if (trace.getSendTime() != null) {
                long latency = Duration.between(trace.getSendTime(), trace.getBrokerReceiveTime()).toMillis();
                trace.setBrokerLatency(latency);
            }
        }
    }

    /**
     * 记录消费开始
     */
    public void recordConsumeStart(String traceId) {
        Level11MessageTrace trace = traces.get(traceId);
        if (trace != null) {
            trace.setConsumerHost("localhost");
            trace.setConsumerApp("rmq-challenge");
            trace.setConsumeStartTime(LocalDateTime.now());

            // 计算消费者延迟
            if (trace.getBrokerReceiveTime() != null) {
                long latency = Duration.between(trace.getBrokerReceiveTime(), trace.getConsumeStartTime()).toMillis();
                trace.setConsumerLatency(latency);
            }
        }
    }

    /**
     * 记录消费结束
     */
    public void recordConsumeEnd(String traceId, boolean success, String errorMessage) {
        Level11MessageTrace trace = traces.get(traceId);
        if (trace != null) {
            trace.setConsumeEndTime(LocalDateTime.now());
            trace.setConsumeResult(success ? "SUCCESS" : "FAILURE");
            trace.setErrorMessage(errorMessage);

            // 计算处理耗时
            if (trace.getConsumeStartTime() != null) {
                long processingTime = Duration.between(trace.getConsumeStartTime(), trace.getConsumeEndTime()).toMillis();
                trace.setProcessingTime(processingTime);
            }

            // 计算总延迟
            if (trace.getSendTime() != null) {
                long totalLat = Duration.between(trace.getSendTime(), trace.getConsumeEndTime()).toMillis();
                trace.setTotalLatency(totalLat);
                totalLatency.addAndGet(totalLat);
            }

            if (success) {
                successMessages.incrementAndGet();
            } else {
                failureMessages.incrementAndGet();
            }

            log.info("✅ [Trace] 消息处理完成 - TraceId: {}, Result: {}, TotalLatency: {}ms",
                    traceId, trace.getConsumeResult(), trace.getTotalLatency());
        }
    }

    /**
     * 记录重试
     */
    public void recordRetry(String traceId) {
        Level11MessageTrace trace = traces.get(traceId);
        if (trace != null) {
            trace.setRetryTimes(trace.getRetryTimes() + 1);
            log.warn("⚠️ [Trace] 消息重试 - TraceId: {}, RetryTimes: {}", traceId, trace.getRetryTimes());
        }
    }

    /**
     * 查询消息轨迹
     */
    public Level11MessageTrace getTrace(String traceId) {
        return traces.get(traceId);
    }

    /**
     * 根据订单 ID 查询轨迹
     */
    public List<Level11MessageTrace> getTracesByOrderId(String orderId) {
        return traces.values().stream()
                .filter(trace -> orderId.equals(trace.getOrderId()))
                .collect(Collectors.toList());
    }

    /**
     * 获取慢消息（处理时间超过阈值）
     */
    public List<Level11MessageTrace> getSlowMessages(long thresholdMs) {
        return traces.values().stream()
                .filter(trace -> trace.getTotalLatency() != null && trace.getTotalLatency() > thresholdMs)
                .sorted((t1, t2) -> Long.compare(t2.getTotalLatency(), t1.getTotalLatency()))
                .collect(Collectors.toList());
    }

    /**
     * 获取失败消息
     */
    public List<Level11MessageTrace> getFailedMessages() {
        return traces.values().stream()
                .filter(trace -> "FAILURE".equals(trace.getConsumeResult()))
                .collect(Collectors.toList());
    }

    /**
     * 获取统计信息
     */
    public String getStats() {
        long avgLatency = totalMessages.get() > 0 ? totalLatency.get() / totalMessages.get() : 0;

        return String.format("""
                📊 Level 11 消息轨迹统计
                - 总消息数: %d
                - 成功数: %d
                - 失败数: %d
                - 成功率: %.2f%%
                - 平均延迟: %d ms
                - 慢消息数(>1000ms): %d
                """,
                totalMessages.get(),
                successMessages.get(),
                failureMessages.get(),
                totalMessages.get() > 0 ? (double) successMessages.get() * 100 / totalMessages.get() : 0,
                avgLatency,
                getSlowMessages(1000).size());
    }

    /**
     * 重置统计
     */
    public void reset() {
        traces.clear();
        totalMessages.set(0);
        successMessages.set(0);
        failureMessages.set(0);
        totalLatency.set(0);
        log.info("🔄 [Trace] 统计已重置");
    }
}
