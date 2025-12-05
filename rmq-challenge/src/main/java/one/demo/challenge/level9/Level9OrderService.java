package one.demo.challenge.level9;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class Level9OrderService {

    private final Map<String, Level9Order> orders = new ConcurrentHashMap<>();

    public Level9Order createOrder(String orderId,
                                   String userId,
                                   BigDecimal amount,
                                   Level9ProcessingMode mode) {
        Level9Order order = new Level9Order();
        order.setOrderId(orderId);
        order.setUserId(userId);
        order.setAmount(amount);
        order.setMode(mode);
        order.setStatus(Level9OrderStatus.PENDING);
        order.setCreatedAt(Instant.now());
        order.setLastUpdatedAt(order.getCreatedAt());
        orders.put(orderId, order);
        log.info("📝 [Level9] 记录订单 - {}", order);
        return order;
    }

    public Optional<Level9Order> getOrder(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    public Collection<Level9Order> listOrders() {
        return orders.values();
    }

    public void reset() {
        orders.clear();
    }

    public void markProcessing(String orderId) {
        update(orderId, Level9OrderStatus.PROCESSING, null);
    }

    public void markSuccess(String orderId) {
        update(orderId, Level9OrderStatus.SUCCESS, null);
    }

    public void markFailed(String orderId, String reason) {
        update(orderId, Level9OrderStatus.FAILED, reason);
    }

    public void markDeadLetter(String orderId, String reason) {
        update(orderId, Level9OrderStatus.DEAD_LETTERED, reason);
    }

    public void incrementAttempt(String orderId) {
        orders.computeIfPresent(orderId, (id, order) -> {
            order.setConsumedAttempts(order.getConsumedAttempts() + 1);
            order.setLastUpdatedAt(Instant.now());
            return order;
        });
    }

    private void update(String orderId, Level9OrderStatus status, String error) {
        orders.computeIfPresent(orderId, (id, order) -> {
            order.setStatus(status);
            order.setLastUpdatedAt(Instant.now());
            if (error != null) {
                order.addError(error);
            }
            return order;
        });
    }
}
