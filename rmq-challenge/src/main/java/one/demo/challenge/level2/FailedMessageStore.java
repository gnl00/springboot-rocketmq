package one.demo.challenge.level2;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 失败消息存储服务
 *
 * 生产环境中应该：
 * 1. 持久化到数据库（如 MySQL、PostgreSQL）
 * 2. 或者写入 Redis 队列
 * 3. 或者写入本地文件
 *
 * 这里为了演示简化，使用内存存储
 */
@Slf4j
@Service
public class FailedMessageStore {

    // 使用 ConcurrentHashMap 存储失败消息（生产环境应该用数据库）
    private final ConcurrentMap<String, FailedMessage> failedMessages = new ConcurrentHashMap<>();

    /**
     * 保存失败消息
     */
    public void save(FailedMessage message) {
        failedMessages.put(message.getMessageId(), message);
        log.warn("失败消息已保存 - MessageId: {}, Topic: {}, Reason: {}",
                message.getMessageId(), message.getTopic(), message.getFailReason());
    }

    /**
     * 获取所有失败消息
     */
    public List<FailedMessage> getAllFailedMessages() {
        return new ArrayList<>(failedMessages.values());
    }

    /**
     * 获取待重试的消息（状态为 PENDING 且重试次数 < 3）
     */
    public List<FailedMessage> getPendingMessages() {
        List<FailedMessage> pending = new ArrayList<>();
        for (FailedMessage msg : failedMessages.values()) {
            if ("PENDING".equals(msg.getStatus()) && msg.getRetryCount() < 3) {
                pending.add(msg);
            }
        }
        return pending;
    }

    /**
     * 更新消息状态
     */
    public void updateStatus(String messageId, String status) {
        FailedMessage message = failedMessages.get(messageId);
        if (message != null) {
            message.setStatus(status);
            log.info("更新失败消息状态 - MessageId: {}, Status: {}", messageId, status);
        }
    }

    /**
     * 增加重试次数
     */
    public void incrementRetryCount(String messageId) {
        FailedMessage message = failedMessages.get(messageId);
        if (message != null) {
            message.setRetryCount(message.getRetryCount() + 1);
            log.info("增加重试次数 - MessageId: {}, RetryCount: {}", messageId, message.getRetryCount());
        }
    }

    /**
     * 删除成功的消息
     */
    public void remove(String messageId) {
        failedMessages.remove(messageId);
        log.info("删除失败消息 - MessageId: {}", messageId);
    }

    /**
     * 获取失败消息统计
     */
    public String getStatistics() {
        long pending = failedMessages.values().stream()
                .filter(m -> "PENDING".equals(m.getStatus()))
                .count();
        long retrying = failedMessages.values().stream()
                .filter(m -> "RETRYING".equals(m.getStatus()))
                .count();
        long failed = failedMessages.values().stream()
                .filter(m -> "FAILED".equals(m.getStatus()))
                .count();

        return String.format("失败消息统计 - 总数: %d, 待重试: %d, 重试中: %d, 最终失败: %d",
                failedMessages.size(), pending, retrying, failed);
    }
}