package one.demo.challenge.level3;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 订单服务（模拟）
 * 生产环境应该操作数据库
 */
@Slf4j
@Service
public class OrderService {

    // 使用内存模拟数据库
    private final ConcurrentMap<String, Order> orders = new ConcurrentHashMap<>();

    /**
     * 创建订单
     */
    public Order createOrder(String orderId, String userId, BigDecimal amount) {
        Order order = new Order(
                orderId,
                userId,
                amount,
                "CREATED",
                LocalDateTime.now(),
                LocalDateTime.now(),
                null
        );
        orders.put(orderId, order);
        log.info("订单创建成功 - OrderId: {}, UserId: {}, Amount: {}", orderId, userId, amount);
        return order;
    }

    /**
     * 更新订单状态为已支付
     */
    public void updateOrderToPaid(String orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在: " + orderId);
        }

        order.setStatus("PAID");
        order.setPayTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());

        log.info("订单状态更新为已支付 - OrderId: {}", orderId);
    }

    /**
     * 获取订单
     */
    public Order getOrder(String orderId) {
        return orders.get(orderId);
    }

    /**
     * 检查订单是否已支付
     */
    public boolean isPaid(String orderId) {
        Order order = orders.get(orderId);
        return order != null && "PAID".equals(order.getStatus());
    }

    /**
     * 获取所有订单
     */
    public ConcurrentMap<String, Order> getAllOrders() {
        return orders;
    }
}