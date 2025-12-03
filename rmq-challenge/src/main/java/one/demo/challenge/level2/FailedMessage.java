package one.demo.challenge.level2;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 失败消息实体类
 * 用于持久化发送失败的消息，便于后续补偿重试
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FailedMessage {

    /**
     * 消息ID（唯一标识）
     */
    private String messageId;

    /**
     * 主题
     */
    private String topic;

    /**
     * 标签
     */
    private String tag;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 失败原因
     */
    private String failReason;

    /**
     * 失败时间
     */
    private LocalDateTime failTime;

    /**
     * 重试次数
     */
    private int retryCount;

    /**
     * 状态：PENDING(待重试), RETRYING(重试中), SUCCESS(成功), FAILED(最终失败)
     */
    private String status;
}