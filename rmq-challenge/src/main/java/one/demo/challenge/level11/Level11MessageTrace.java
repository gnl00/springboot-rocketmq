package one.demo.challenge.level11;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Level 11 消息轨迹
 * 记录消息的完整生命周期
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Level11MessageTrace {
    private String traceId;           // 全局追踪 ID
    private String messageId;         // 消息 ID
    private String orderId;           // 业务 ID

    // 生产者信息
    private String producerHost;
    private String producerApp;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private LocalDateTime sendTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private LocalDateTime brokerReceiveTime;

    // 消费者信息
    private String consumerHost;
    private String consumerApp;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private LocalDateTime consumeStartTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private LocalDateTime consumeEndTime;

    private String consumeResult;     // SUCCESS / FAILURE
    private String errorMessage;      // 错误信息

    // 性能指标
    private Long brokerLatency;       // Broker 接收延迟（ms）
    private Long consumerLatency;     // 消费者拉取延迟（ms）
    private Long processingTime;      // 处理耗时（ms）
    private Long totalLatency;        // 总延迟（ms）

    // 重试信息
    private Integer retryTimes;       // 重试次数
}
