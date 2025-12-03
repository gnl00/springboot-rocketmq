package one.demo.challenge.level5;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订单状态管理器
 * 用于追踪和验证订单状态变更的正确性
 */
@Slf4j
@Service
public class OrderStatusManager {

    // 订单当前状态
    private final Map<String, OrderStatus> orderCurrentStatus = new ConcurrentHashMap<>();

    // 订单最后处理的序列号
    private final Map<String, Integer> orderLastSequence = new ConcurrentHashMap<>();

    // 统计数据
    private final Map<String, Integer> orderErrorCount = new ConcurrentHashMap<>();
    private final Map<String, Integer> orderSuccessCount = new ConcurrentHashMap<>();

    /**
     * 更新订单状态
     *
     * @param orderId 订单ID
     * @param newStatus 新状态
     * @param sequenceNo 序列号
     * @return 是否更新成功
     */
    public boolean updateStatus(String orderId, OrderStatus newStatus, int sequenceNo) {
        OrderStatus currentStatus = orderCurrentStatus.get(orderId);
        Integer lastSeq = orderLastSequence.getOrDefault(orderId, -1);

        // 验证序列号是否连续
        if (sequenceNo <= lastSeq) {
            log.warn("⚠️ 订单 {} 收到乱序消息！当前序列号: {}, 收到序列号: {}", orderId, lastSeq, sequenceNo);
            orderErrorCount.merge(orderId, 1, Integer::sum);
            return false;
        }

        // 验证状态转换是否合法
        if (!isValidTransition(currentStatus, newStatus)) {
            log.error("❌ 订单 {} 状态转换非法！{} -> {}", orderId, currentStatus, newStatus);
            orderErrorCount.merge(orderId, 1, Integer::sum);
            return false;
        }

        // 更新状态
        orderCurrentStatus.put(orderId, newStatus);
        orderLastSequence.put(orderId, sequenceNo);
        orderSuccessCount.merge(orderId, 1, Integer::sum);

        log.info("✅ 订单 {} 状态更新成功: {} (seq={})", orderId, newStatus.getDescription(), sequenceNo);
        return true;
    }

    /**
     * 验证状态转换是否合法
     */
    private boolean isValidTransition(OrderStatus current, OrderStatus next) {
        if (current == null) {
            return next == OrderStatus.CREATED;
        }

        switch (current) {
            case CREATED:
                return next == OrderStatus.PAID || next == OrderStatus.CANCELLED;
            case PAID:
                return next == OrderStatus.SHIPPED || next == OrderStatus.CANCELLED;
            case SHIPPED:
                return next == OrderStatus.COMPLETED;
            case COMPLETED:
            case CANCELLED:
                return false;  // 终态，不能再转换
            default:
                return false;
        }
    }

    /**
     * 获取订单当前状态
     */
    public OrderStatus getCurrentStatus(String orderId) {
        return orderCurrentStatus.get(orderId);
    }

    /**
     * 获取统计信息
     */
    public String getStatistics(String orderId) {
        OrderStatus status = orderCurrentStatus.get(orderId);
        int success = orderSuccessCount.getOrDefault(orderId, 0);
        int error = orderErrorCount.getOrDefault(orderId, 0);
        int lastSeq = orderLastSequence.getOrDefault(orderId, -1);

        return String.format("订单 %s - 当前状态: %s, 成功: %d, 错误: %d, 最后序列号: %d",
                orderId,
                status != null ? status.getDescription() : "未知",
                success, error, lastSeq);
    }

    /**
     * 重置订单状态（用于测试）
     */
    public void reset(String orderId) {
        orderCurrentStatus.remove(orderId);
        orderLastSequence.remove(orderId);
        orderErrorCount.remove(orderId);
        orderSuccessCount.remove(orderId);
        log.info("🔄 订单 {} 状态已重置", orderId);
    }

    /**
     * 重置所有订单状态
     */
    public void resetAll() {
        orderCurrentStatus.clear();
        orderLastSequence.clear();
        orderErrorCount.clear();
        orderSuccessCount.clear();
        log.info("🔄 所有订单状态已重置");
    }
}
