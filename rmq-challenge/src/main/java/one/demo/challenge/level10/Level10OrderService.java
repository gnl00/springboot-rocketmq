package one.demo.challenge.level10;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Level 10 订单服务
 */
@Slf4j
@Service
public class Level10OrderService {

    private final Map<String, Level10Order> orders = new ConcurrentHashMap<>();
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger batchProcessedCount = new AtomicInteger(0);

    /**
     * 保存订单
     */
    public void saveOrder(Level10Order order) {
        orders.put(order.getOrderId(), order);
        log.info("💾 订单已保存 - OrderId: {}, Type: {}", order.getOrderId(), order.getOrderType());
    }

    /**
     * 处理单个订单
     */
    public void processOrder(String orderId) {
        Level10Order order = orders.get(orderId);
        if (order != null) {
            order.setStatus("PROCESSED");
            processedCount.incrementAndGet();
            log.info("✅ 订单处理完成 - OrderId: {}", orderId);
        }
    }

    /**
     * 批量处理订单（模拟数据库批量操作）
     */
    public void batchProcessOrders(List<String> orderIds) {
        log.info("🔄 开始批量处理 {} 个订单", orderIds.size());

        // 模拟批量数据库操作
        for (String orderId : orderIds) {
            Level10Order order = orders.get(orderId);
            if (order != null) {
                order.setStatus("BATCH_PROCESSED");
            }
        }

        batchProcessedCount.addAndGet(orderIds.size());
        log.info("✅ 批量处理完成 - 处理数量: {}", orderIds.size());
    }

    /**
     * 获取订单
     */
    public Level10Order getOrder(String orderId) {
        return orders.get(orderId);
    }

    /**
     * 获取统计信息
     */
    public String getStats() {
        return String.format("""
                📊 Level 10 订单统计
                - 总订单数: %d
                - 单个处理数: %d
                - 批量处理数: %d
                """, orders.size(), processedCount.get(), batchProcessedCount.get());
    }

    /**
     * 重置统计
     */
    public void reset() {
        orders.clear();
        processedCount.set(0);
        batchProcessedCount.set(0);
        log.info("🔄 统计已重置");
    }
}
