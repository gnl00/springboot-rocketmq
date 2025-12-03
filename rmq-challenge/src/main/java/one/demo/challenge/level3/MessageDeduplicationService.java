package one.demo.challenge.level3;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 消息去重服务
 *
 * 生产环境应该：
 * 1. 使用数据库表记录（带唯一索引）
 * 2. 或使用 Redis（带过期时间）
 *
 * 这里为了演示简化，使用内存存储
 */
@Slf4j
@Service
public class MessageDeduplicationService {

    /**
     * 已处理的消息
     * Key: messageId 或 orderId
     * Value: 处理时间
     */
    private final ConcurrentMap<String, LocalDateTime> processedMessages = new ConcurrentHashMap<>();

    /**
     * 尝试处理消息（原子操作）
     *
     * @param messageId 消息ID
     * @return true-首次处理，false-重复消息
     */
    public boolean tryProcess(String messageId) {
        // 使用 putIfAbsent 保证原子性
        LocalDateTime existingTime = processedMessages.putIfAbsent(messageId, LocalDateTime.now());

        if (existingTime == null) {
            log.info("首次处理消息 - MessageId: {}", messageId);
            return true;  // 首次处理
        } else {
            log.warn("检测到重复消息 - MessageId: {}, 首次处理时间: {}", messageId, existingTime);
            return false;  // 重复消息
        }
    }

    /**
     * 检查消息是否已处理
     */
    public boolean isProcessed(String messageId) {
        return processedMessages.containsKey(messageId);
    }

    /**
     * 获取已处理消息数量
     */
    public int getProcessedCount() {
        return processedMessages.size();
    }

    /**
     * 清理过期记录（实际应该用定时任务）
     * 这里仅用于演示
     */
    public void cleanExpired(int hours) {
        LocalDateTime expireTime = LocalDateTime.now().minusHours(hours);
        processedMessages.entrySet().removeIf(entry -> entry.getValue().isBefore(expireTime));
        log.info("清理过期记录完成，当前记录数: {}", processedMessages.size());
    }
}