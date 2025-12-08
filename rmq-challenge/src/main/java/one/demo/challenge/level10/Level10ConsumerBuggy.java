package one.demo.challenge.level10;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Level 10 消费者（Buggy 版本）
 *
 * Bug 分析：
 * 1. 逐条处理消息，没有批量处理优化，导致数据库压力大
 * 2. 线程数配置过少（默认只有 1 个线程），无法充分利用 CPU
 * 3. 没有本地缓存队列，无法实现批量提交
 * 4. 没有流量控制，高峰期可能导致 OOM
 * 5. 每条消息都调用一次数据库，性能低下
 *
 * 问题现象：
 * 1. 处理速度慢，消息积压
 * 2. 数据库连接数暴增
 * 3. CPU 利用率低
 * 4. 高峰期内存溢出
 */
@Slf4j
// @Component
@RocketMQMessageListener(
        topic = Level10Constants.BATCH_ORDER_TOPIC,
        consumerGroup = Level10Constants.CONSUMER_GROUP,
        endpoints = Level10Constants.ENDPOINTS,
        tag = "*",
        // Bug 1: 线程数配置过少，默认只有 1 个线程
        consumptionThreadCount = 1,
        // Bug 2: 没有配置批量拉取大小，默认逐条拉取
        maxCachedMessageCount = 1
)
public class Level10ConsumerBuggy implements RocketMQListener {

    @Autowired
    private Level10OrderService orderService;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules(); // 自动注册 JSR310 模块支持 Java 8 日期时间

    @Override
    public ConsumeResult consume(MessageView messageView) {
        String body = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();

        try {
            Level10Order order = objectMapper.readValue(body, Level10Order.class);

            // Bug 3: 逐条处理消息，每次都调用数据库
            // 没有批量处理优化，导致数据库压力大
            processOrderOneByOne(order);

            log.info("✅ [Buggy] 订单处理成功 - OrderId: {}, Type: {}",
                    order.getOrderId(), order.getOrderType());

            return ConsumeResult.SUCCESS;

        } catch (Exception e) {
            log.error("❌ [Buggy] 订单处理失败", e);
            return ConsumeResult.FAILURE;
        }
    }

    /**
     * 逐条处理订单（Buggy 版本）
     * Bug: 每条消息都调用一次数据库，性能低下
     */
    private void processOrderOneByOne(Level10Order order) {
        // Bug 4: 模拟数据库操作，每次都建立连接
        // 实际场景中，这会导致数据库连接数暴增
        simulateDatabaseOperation(order);

        // Bug 5: 没有批量提交，每条消息都单独提交
        orderService.processOrder(order.getOrderId());

        // Bug 6: 模拟处理耗时，但没有异步处理机制
        try {
            Thread.sleep(10); // 模拟业务处理耗时
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 模拟数据库操作
     */
    private void simulateDatabaseOperation(Level10Order order) {
        // Bug: 每次都执行单条 SQL，没有批量操作
        // 实际场景：
        // - 单条 INSERT: 1ms
        // - 批量 INSERT (100条): 10ms
        // - 性能差距: 10倍
        log.debug("💾 [Buggy] 执行单条数据库操作 - OrderId: {}", order.getOrderId());

        try {
            // 模拟数据库 IO 耗时
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
