package one.demo.challenge.level12;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Level 12 消息实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Level12Message implements Serializable {

    /**
     * 消息 ID
     */
    private String messageId;

    /**
     * Topic
     */
    private String topic;

    /**
     * Tag
     */
    private String tag;

    /**
     * 消息 Key
     */
    private String key;

    /**
     * 消息体
     */
    private String body;

    /**
     * 创建时间
     */
    private long createTime;

    /**
     * 存储时间
     */
    private long storeTime;

    /**
     * 队列 ID
     */
    private int queueId;

    /**
     * 队列偏移量
     */
    private long queueOffset;

    /**
     * 物理偏移量（CommitLog 中的位置）
     */
    private long physicalOffset;

    private int len;
}
