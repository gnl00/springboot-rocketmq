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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Level 10 消费者（Fixed 版本）
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
@Component
@RocketMQMessageListener(
        topic = Level10Constants.BATCH_ORDER_TOPIC,
        consumerGroup = Level10Constants.CONSUMER_GROUP,
        endpoints = Level10Constants.ENDPOINTS,
        tag = "*"
        // consumptionThreadCount = 1,
        // maxCachedMessageCount = 1
)
public class Level10ConsumerFixed implements RocketMQListener {

    @Autowired
    private Level10OrderService orderService;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules(); // 自动注册 JSR310 模块支持 Java 8 日期时间

    private static final LinkedBlockingQueue<Level10Order> orders = new LinkedBlockingQueue<>();

    private static final AtomicInteger totalCount = new AtomicInteger(0);

    private static final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 2);

    private static volatile AtomicReference<ScheduledFuture<?>> scheduledFuture = new AtomicReference<>();

    @Override
    public ConsumeResult consume(MessageView messageView) {
        String body = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();
        try {
            Level10Order order = objectMapper.readValue(body, Level10Order.class);
            orders.offer(order);
            int i = totalCount.incrementAndGet();
            log.info("订单开始处理 - count={} orders.size()={}", i, orders.size());
            if (orders.size() == 100) {
                if (scheduledFuture.get() != null) {
                    scheduledFuture.get().cancel(true);
                }
                // add a timer to execute the batch processing after a certain time
                Thread delayTh = new Thread(() -> {
                    log.info("执行收尾任务，剩余 orders.size() = {}", orders.size());
                    if (!orders.isEmpty()) {
                        processOrderBatch(orders);
                    }
                });
                ScheduledFuture<?> schedule = scheduledExecutorService.schedule(delayTh, 5 * 1000, TimeUnit.MILLISECONDS);
                scheduledFuture.set(schedule);
                processOrderBatch(orders);
            }
            log.info("✅ [Fixed] 订单处理成功 - OrderId: {}, Type: {} count: {}",
                    order.getOrderId(), order.getOrderType(), i);
            return ConsumeResult.SUCCESS;
        } catch (Exception e) {
            log.error("❌ [Fixed] 订单处理失败", e);
            return ConsumeResult.FAILURE;
        }
    }

    /**
     * 逐条处理订单（Fixed 版本）
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

    private synchronized void processOrderBatch(BlockingQueue<Level10Order> orders) {
        ArrayList<Level10Order> list = new ArrayList<>();
        orders.drainTo(list, 100);
        // Bug 4: 模拟数据库操作，每次都建立连接
        // 实际场景中，这会导致数据库连接数暴增
        simulateDatabaseOperationBatch(list);

        // Bug 5: 没有批量提交，每条消息都单独提交
        orderService.batchProcessOrders(list.stream().map(Level10Order::getOrderId).toList());

        // Bug 6: 模拟处理耗时，但没有异步处理机制
        new Thread(() -> {
            try {
                Thread.sleep(10); // 模拟业务处理耗时
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
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
        log.debug("💾 [Fixed] 执行单条数据库操作 - OrderId: {}", order.getOrderId());

        try {
            // 模拟数据库 IO 耗时
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void simulateDatabaseOperationBatch(List<Level10Order> orders) {
        // Bug: 每次都执行单条 SQL，没有批量操作
        // 实际场景：
        // - 单条 INSERT: 1ms
        // - 批量 INSERT (100条): 10ms
        // - 性能差距: 10倍
        List<String> orderIds = orders.stream().map(Level10Order::getOrderId).toList();
        log.debug("💾 [Fixed] 执行数据库操作 - OrderIdList: {}", orderIds);

        try {
            // 模拟数据库 IO 耗时
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
