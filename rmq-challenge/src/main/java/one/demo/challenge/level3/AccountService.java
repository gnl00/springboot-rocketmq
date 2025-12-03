package one.demo.challenge.level3;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 账户服务（模拟）
 * 用于模拟扣款操作
 */
@Slf4j
@Service
public class AccountService {

    // 模拟账户余额（用户ID -> 余额）
    private final ConcurrentMap<String, BigDecimal> balances = new ConcurrentHashMap<>();

    public AccountService() {
        // 初始化一些测试账户
        balances.put("user001", new BigDecimal("1000.00"));
        balances.put("user002", new BigDecimal("500.00"));
        balances.put("user003", new BigDecimal("2000.00"));
    }

    /**
     * 扣款
     * Bug：没有幂等性保护，重复调用会重复扣款！
     */
    public void deduct(String userId, BigDecimal amount, String orderId) {
        BigDecimal currentBalance = balances.get(userId);

        if (currentBalance == null) {
            throw new RuntimeException("用户不存在: " + userId);
        }

        if (currentBalance.compareTo(amount) < 0) {
            throw new RuntimeException("余额不足: " + userId);
        }

        // Bug: 没有检查是否已经扣过款，重复消费会重复扣款！
        BigDecimal newBalance = currentBalance.subtract(amount);
        balances.put(userId, newBalance);

        log.warn("【重要】扣款成功 - UserId: {}, Amount: {}, 剩余余额: {}, OrderId: {}",
                userId, amount, newBalance, orderId);
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