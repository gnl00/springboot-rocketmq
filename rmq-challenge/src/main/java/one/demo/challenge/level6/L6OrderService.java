package one.demo.challenge.level6;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订单服务（模拟数据库操作）
 */
@Slf4j
@Service
public class L6OrderService {

    // 模拟数据库存储
    private final Map<String, L6Order> orderDatabase = new ConcurrentHashMap<>();

    /**
     * 创建订单（本地事务）
     */
    public L6Order createOrder(L6Order l6Order) {
        log.info("💾 [DB] 创建订单 - OrderId: {}, ProductId: {}, Quantity: {}",
                l6Order.getOrderId(), l6Order.getProductId(), l6Order.getQuantity());

        l6Order.setState(L6OrderState.PENDING);
        orderDatabase.put(l6Order.getOrderId(), l6Order);

        return l6Order;
    }

    /**
     * 确认订单
     */
    public void confirmOrder(String orderId) {
        L6Order l6Order = orderDatabase.get(orderId);
        if (l6Order != null) {
            l6Order.setState(L6OrderState.CONFIRMED);
            log.info("✅ [DB] 订单已确认 - OrderId: {}", orderId);
        }
    }

    /**
     * 取消订单
     */
    public void cancelOrder(String orderId) {
        L6Order l6Order = orderDatabase.get(orderId);
        if (l6Order != null) {
            l6Order.setState(L6OrderState.CANCELLED);
            log.info("❌ [DB] 订单已取消 - OrderId: {}", orderId);
        }
    }

    /**
     * 查询订单
     */
    public L6Order getOrder(String orderId) {
        return orderDatabase.get(orderId);
    }

    /**
     * 获取所有订单
     */
    public Map<String, L6Order> getAllOrders() {
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
