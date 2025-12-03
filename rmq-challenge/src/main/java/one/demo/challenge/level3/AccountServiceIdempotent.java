package one.demo.challenge.level3;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 账户服务（幂等版本）
 * 支持幂等扣款
 */
@Slf4j
@Service
public class AccountServiceIdempotent {

    // 模拟账户余额（用户ID -> 余额）
    private final ConcurrentMap<String, BigDecimal> balances = new ConcurrentHashMap<>();

    // 记录已处理的扣款请求（orderId -> true）
    private final ConcurrentMap<String, Boolean> deductionRecords = new ConcurrentHashMap<>();

    public AccountServiceIdempotent() {
        // 初始化一些测试账户
        balances.put("user001", new BigDecimal("1000.00"));
        balances.put("user002", new BigDecimal("500.00"));
        balances.put("user003", new BigDecimal("2000.00"));
    }

    /**
     * 幂等扣款
     * 使用订单ID作为幂等键，同一个订单只会扣款一次
     */
    public synchronized boolean deductIdempotent(String userId, BigDecimal amount, String orderId) {
        // 幂等性检查：如果已经扣过款，直接返回成功
        if (deductionRecords.containsKey(orderId)) {
            log.info("订单 {} 已经扣款，跳过重复扣款", orderId);
            return true;
        }

        BigDecimal currentBalance = balances.get(userId);

        if (currentBalance == null) {
            log.error("用户不存在: {}", userId);
            throw new RuntimeException("用户不存在: " + userId);
        }

        if (currentBalance.compareTo(amount) < 0) {
            log.error("余额不足 - UserId: {}, 当前余额: {}, 需要金额: {}",
                userId, currentBalance, amount);
            throw new RuntimeException("余额不足");
        }

        // 执行扣款
        BigDecimal newBalance = currentBalance.subtract(amount);
        balances.put(userId, newBalance);

        // 记录已扣款
        deductionRecords.put(orderId, true);

        log.info("【扣款成功】UserId: {}, Amount: {}, 剩余余额: {}, OrderId: {}",
                userId, amount, newBalance, orderId);

        return true;
    }

    /**
     * 检查订单是否已扣款
     */
    public boolean isDeducted(String orderId) {
        return deductionRecords.containsKey(orderId);
    }

    /**
     * 查询余额
     */
    public BigDecimal getBalance(String userId) {
        return balances.getOrDefault(userId, BigDecimal.ZERO);
    }

    /**
     * 获取所有账户信息
     */
    public ConcurrentMap<String, BigDecimal> getAllBalances() {
        return balances;
    }
}