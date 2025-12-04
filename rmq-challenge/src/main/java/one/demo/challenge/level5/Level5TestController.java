package one.demo.challenge.level5;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/challenge/level5")
public class Level5TestController {

    @Autowired
    private OrderStatusManager orderStatusManager;

    /**
     * 查询订单状态
     */
    @GetMapping("/checkOrderStatus")
    public String checkOrderStatus(@RequestParam String orderId) {
        String statistics = orderStatusManager.getStatistics(orderId);
        OrderStatus currentStatus = orderStatusManager.getCurrentStatus(orderId);

        return String.format("""
                📊 订单状态查询

                %s

                状态说明：
                - 正常流程: 创建 → 支付 → 发货 → 完成
                - 当前状态: %s
                - 如果错误数 > 0，说明出现了乱序或非法状态转换
                """,
                statistics,
                currentStatus != null ? currentStatus.getDescription() : "未知");
    }

    /**
     * 重置订单状态
     */
    @GetMapping("/reset")
    public String reset(@RequestParam(required = false) String orderId) {
        if (orderId != null && !orderId.isEmpty()) {
            orderStatusManager.reset(orderId);
            return "✅ 订单 " + orderId + " 状态已重置";
        } else {
            orderStatusManager.resetAll();
            return "✅ 所有订单状态已重置";
        }
    }

    /**
     * 帮助信息
     */
    @GetMapping("/help")
    public String help() {
        return """
                🎯 Level 5 挑战：消息顺序性问题

                ## 问题场景
                订单状态必须按顺序流转：创建 → 支付 → 发货 → 完成
                但消息发送和消费的无序性导致状态更新混乱。

                ## 测试步骤

                1. 发送单个订单状态流转：
                   curl "http://localhost:8070/challenge/level5/simulateOrderFlow?orderId=ORDER-001"

                2. 查看订单状态：
                   curl "http://localhost:8070/challenge/level5/checkOrderStatus?orderId=ORDER-001"

                3. 并发测试（加剧乱序）：
                   curl "http://localhost:8070/challenge/level5/simulateMultipleOrders?count=5"

                4. 重置测试环境：
                   curl "http://localhost:8070/challenge/level5/reset"

                ## 观察要点

                - 消费者日志：
                  ⚠️ 乱序消息警告
                  ❌ 状态转换非法错误

                - 订单状态统计：
                  错误数量 > 0 说明出现问题

                ## 问题分析

                Buggy 版本的问题：
                1. 生产者使用普通消息，RocketMQ 随机选择队列
                2. 消费者并发消费，多线程同时处理
                3. 消息到达顺序无法保证

                ## 解决方案提示

                1. 生产者：使用 MessageQueueSelector 指定队列
                2. 消费者：使用顺序消费模式（Orderly）
                3. 分区策略：按订单ID分区，同一订单进入同一队列

                ## 挑战任务

                1. 运行测试，观察乱序现象
                2. 分析为什么会乱序
                3. 创建 Level5ProducerFixed 和 Level5ConsumerFixed
                4. 实现顺序消息发送和消费
                5. 验证修复效果

                准备好了吗？开始你的挑战！🚀
                """;
    }
}
