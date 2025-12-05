package one.demo.challenge.level7;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订单服务
 */
@Slf4j
@Service
public class OrderService {

    @Autowired
    private InventoryService inventoryService;

    // 模拟数据库存储
    private final Map<String, Order> orderDatabase = new ConcurrentHashMap<>();

    /**
     * 创建订单
     */
    public Order createOrder(Order order) {
        log.info("💾 [订单] 创建订单 - OrderId: {}, UserId: {}, Amount: {}, ExpireTime: {}",
                order.getOrderId(), order.getUserId(), order.getAmount(), order.getExpireTime());

        order.setStatus(OrderStatus.PENDING);
        orderDatabase.put(order.getOrderId(), order);

        return order;
    }

    /**
     * 支付订单
     */
    public boolean payOrder(String orderId) {
        Order order = orderDatabase.get(orderId);
        if (order == null) {
            log.warn("⚠️ [订单] 订单不存在 - OrderId: ", orderId);
            return false;
        }

        if (order.getStatus() != OrderStatus.PENDING) {
            log.warn("⚠️ [订单] 订单状态不是待支付 - OrderId: {}, Status: {}", orderId, order.getStatus());
            return false;
        }

        order.setStatus(OrderStatus.PAID);
        log.info("✅ [订单] 订单支付成功 - OrderId: {}", orderId);
        return true;
    }

    /**
     * 取消订单（超时自动取消）
     */
    public boolean cancelOrder(String orderId) {
        Order order = orderDatabase.get(orderId);
        if (order == null) {
            log.warn("⚠️ [订单] 订单不存在 - OrderId: {}", orderId);
            return false;
        }

        if (order.getStatus() != OrderStatus.PENDING) {
            log.warn("⚠️ [订单] 订单状态不是待支付，无法取消 - OrderId: {}, Status: {}",
                    orderId, order.getStatus());
            return false;
        }

        // 恢复库存
        inventoryService.restoreInventory(order.getProductId(), order.getQuantity());

        order.setStatus(OrderStatus.CANCELLED);
        log.info("✅ [订单] 订单已取消，库存已恢复 - OrderId: {}", orderId);
        return true;
    }

    /**
     * 查询订单
     */
    public Order getOrder(String orderId) {
        return orderDatabase.get(orderId);
    }

    /**
     * 获取所有订单
     */
    public Map<String, Order> getAllOrders() {
        return new ConcurrentHashMap<>(orderDatabase);
    }

    /**
     * 重置所有订单
     */
    public void reset() {
        orderDatabase.clear();
        log.info("🔄 订单数据已重置");
    }
}
