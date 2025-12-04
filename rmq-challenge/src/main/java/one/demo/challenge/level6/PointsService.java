package one.demo.challenge.level6;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 积分服务（下游服务）
 */
@Slf4j
@Service
public class PointsService {

    // 模拟积分数据库
    private final Map<String, Integer> pointsDatabase = new ConcurrentHashMap<>();

    public PointsService() {
        // 初始化用户积分
        pointsDatabase.put("USER-001", 0);
        pointsDatabase.put("USER-002", 0);
        pointsDatabase.put("USER-003", 0);
    }

    /**
     * 增加积分（订单金额的10%）
     */
    public void addPoints(String userId, BigDecimal orderAmount) {
        int points = orderAmount.multiply(BigDecimal.valueOf(0.1)).intValue();
        Integer currentPoints = pointsDatabase.getOrDefault(userId, 0);
        pointsDatabase.put(userId, currentPoints + points);

        log.info("⭐ [积分] 增加成功 - UserId: {}, Points: +{}, 当前: {}",
                userId, points, currentPoints + points);
    }

    /**
     * 扣减积分（订单取消时）
     */
    public void deductPoints(String userId, BigDecimal orderAmount) {
        int points = orderAmount.multiply(BigDecimal.valueOf(0.1)).intValue();
        Integer currentPoints = pointsDatabase.getOrDefault(userId, 0);
        pointsDatabase.put(userId, Math.max(0, currentPoints - points));

        log.info("⭐ [积分] 扣减成功 - UserId: {}, Points: -{}, 当前: {}",
                userId, points, Math.max(0, currentPoints - points));
    }

    /**
     * 查询积分
     */
    public Integer getPoints(String userId) {
        return pointsDatabase.getOrDefault(userId, 0);
    }

    /**
     * 获取所有积分
     */
    public Map<String, Integer> getAllPoints() {
        return new ConcurrentHashMap<>(pointsDatabase);
    }

    /**
     * 重置积分
     */
    public void reset() {
        pointsDatabase.clear();
        pointsDatabase.put("USER-001", 0);
        pointsDatabase.put("USER-002", 0);
        pointsDatabase.put("USER-003", 0);
        log.info("🔄 积分数据已重置");
    }
}
