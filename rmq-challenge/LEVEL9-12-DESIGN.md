# Level 9-12: 高级挑战设计文档

## Level 9: 死信队列与消息重试 ⭐⭐⭐⭐

### 问题场景
消息消费失败后，如何处理？重试多少次？重试失败后怎么办？

### 核心挑战
1. 区分业务异常和系统异常
2. 设置合理的重试次数和间隔
3. 死信消息的监控和处理
4. 死信消息的重新投递

### Buggy 版本问题
```java
// Bug 1: 无限重试，消耗系统资源
return ConsumeResult.FAILURE; // 一直重试

// Bug 2: 业务异常也重试
if (order.getAmount() < 0) {
    throw new RuntimeException("金额不能为负数"); // 业务异常，不应该重试
}

// Bug 3: 死信队列未监控
// 消息进入死信队列后，无人处理，消息丢失

// Bug 4: 重试间隔不合理
// 立即重试，没有退避策略
```

### 解决方案
```java
// 1. 区分异常类型
try {
    processMessage(message);
} catch (BusinessException e) {
    // 业务异常，不重试
    log.error("业务异常，不重试", e);
    return ConsumeResult.SUCCESS; // 返回成功，避免重试
} catch (SystemException e) {
    // 系统异常，重试
    log.error("系统异常，重试", e);
    return ConsumeResult.FAILURE;
}

// 2. 设置重试次数
@RocketMQMessageListener(
    topic = "order-topic",
    consumerGroup = "order-consumer",
    maxReconsumeTimes = 3 // 最多重试 3 次
)

// 3. 监控死信队列
@RocketMQMessageListener(
    topic = "%DLQ%order-consumer", // 死信队列
    consumerGroup = "dlq-monitor"
)
public class DLQMonitor implements RocketMQListener {
    @Override
    public ConsumeResult consume(MessageView message) {
        // 记录死信消息
        log.error("死信消息: {}", message);
        // 发送告警
        alertService.sendAlert("死信消息", message);
        // 持久化到数据库
        dlqMessageService.save(message);
        return ConsumeResult.SUCCESS;
    }
}

// 4. 重新投递死信消息
public void retryDLQMessage(String messageId) {
    DLQMessage dlqMessage = dlqMessageService.findById(messageId);
    // 重新发送到原始 Topic
    producer.send(dlqMessage.getOriginalTopic(), dlqMessage.getBody());
}
```

### 测试场景
1. 模拟系统异常，验证重试机制
2. 模拟业务异常，验证不重试
3. 重试 3 次后，验证进入死信队列
4. 监控死信队列，验证告警
5. 重新投递死信消息，验证成功消费

---

## Level 10: 消息批量处理与流量控制 ⭐⭐⭐⭐

### 问题场景
电商系统需要处理大量订单消息，为了提高性能需要实现批量处理，但当前实现存在多个问题导致性能低下、资源浪费、消息积压。

### 核心挑战
1. 消息批量处理优化
2. 流量控制与背压机制
3. 异常隔离与容错
4. 线程池合理配置

### Buggy 版本问题
```java
// Bug 1: 逐条处理消息，性能低下
@Override
public ConsumeResult consume(MessageView message) {
    processOrderOneByOne(order); // 每条消息都调用一次数据库
    return ConsumeResult.SUCCESS;
}

// Bug 2: 批量发送时一条失败导致整批失败
for (int i = 0; i < count; i++) {
    producer.send(message); // 没有异常隔离
}

// Bug 3: 没有流量控制，高峰期 OOM
// 无限制接收消息，内存溢出

// Bug 4: 线程数配置不合理
consumptionThreadCount = 1 // CPU 利用率低
```

### 解决方案
```java
// 1. 本地队列缓存 + 批量处理
private final BlockingQueue<Order> orderQueue = new LinkedBlockingQueue<>(1000);

@Override
public ConsumeResult consume(MessageView message) {
    Order order = parse(message);
    orderQueue.offer(order); // 放入本地队列

    // 达到阈值时批量处理
    if (orderQueue.size() >= BATCH_SIZE) {
        processBatch();
    }
    return ConsumeResult.SUCCESS;
}

private void processBatch() {
    List<Order> batch = new ArrayList<>();
    orderQueue.drainTo(batch, 100); // 批量取出
    orderService.batchProcess(batch); // 批量提交数据库
}

// 2. 异常隔离
for (int i = 0; i < count; i++) {
    try {
        producer.send(message);
        successCount++;
    } catch (Exception e) {
        failedCount++;
        // 单条失败不影响其他消息
    }
}

// 3. 流量控制
private final Semaphore rateLimiter = new Semaphore(500);

@Override
public ConsumeResult consume(MessageView message) {
    if (!rateLimiter.tryAcquire(100, TimeUnit.MILLISECONDS)) {
        return ConsumeResult.FAILURE; // 触发重试
    }
    try {
        processMessage(message);
    } finally {
        rateLimiter.release();
    }
}

// 4. 合理配置线程数
consumptionThreadCount = Runtime.getRuntime().availableProcessors() * 2
```

### 测试场景
1. 批量发送测试，验证异常隔离
2. 压力测试，验证流量控制
3. 性能对比：逐条 vs 批量处理
4. 监控 CPU、内存使用率

---

## Level 11: 消息轨迹追踪与可观测性 ⭐⭐⭐⭐⭐

### 问题场景
生产环境中，消息系统出现了各种问题：
- 某些订单消息处理很慢，但不知道慢在哪里（发送慢？消费慢？业务处理慢？）
- 消息偶尔丢失，但无法追踪消息的完整生命周期
- 消费失败后，不知道失败原因和重试次数
- 无法统计消息的端到端延迟
- 出现问题时，无法快速定位是哪个环节出了问题

### 核心挑战
1. TraceId 的生成和传递
2. 消息轨迹的完整记录
3. 性能指标的采集与计算
4. 慢消息与失败消息的追踪
5. 可视化与监控告警

### Buggy 版本问题
```java
// Bug 1: 没有生成和传递 TraceId
Message message = builder.setBody(body).build();
// 无法追踪消息链路

// Bug 2: 没有记录关键时间点
producer.send(message);
// 没有记录发送时间、接收时间、处理时间

// Bug 3: 没有记录性能指标
@Override
public ConsumeResult consume(MessageView message) {
    processMessage(message);
    // 没有计算延迟、耗时等指标
}

// Bug 4: 没有错误追踪
catch (Exception e) {
    log.error("处理失败", e);
    // 没有记录错误详情、重试次数
}

// Bug 5: 无法查询轨迹
// 没有提供查询接口：按 TraceId、OrderId、慢消息、失败消息查询
```

### 解决方案
```java
// 1. TraceId 生成与传递
String traceId = UUID.randomUUID().toString();

Message message = provider.newMessageBuilder()
    .setTopic("order-topic")
    .setKeys(orderId)
    .setBody(messageBody)
    .build();

// 在消息体中传递 TraceId
OrderMessage orderMessage = new OrderMessage();
orderMessage.setTraceId(traceId);
orderMessage.setOrderId(orderId);

// 2. 记录发送轨迹
MessageTrace trace = new MessageTrace();
trace.setTraceId(traceId);
trace.setMessageId(receipt.getMessageId());
trace.setSendTime(LocalDateTime.now());
traceService.recordSend(trace);

// 3. 记录消费轨迹
@Override
public ConsumeResult consume(MessageView message) {
    OrderMessage order = parse(message);
    String traceId = order.getTraceId();

    // 记录消费开始
    traceService.recordConsumeStart(traceId);

    long startTime = System.currentTimeMillis();
    try {
        processMessage(order);

        // 记录消费成功
        long duration = System.currentTimeMillis() - startTime;
        traceService.recordConsumeEnd(traceId, true, null, duration);

        return ConsumeResult.SUCCESS;
    } catch (Exception e) {
        // 记录消费失败
        long duration = System.currentTimeMillis() - startTime;
        traceService.recordConsumeEnd(traceId, false, e.getMessage(), duration);

        return ConsumeResult.FAILURE;
    }
}

// 4. 计算性能指标
public class MessageTrace {
    private LocalDateTime sendTime;           // 发送时间
    private LocalDateTime brokerReceiveTime;  // Broker 接收时间
    private LocalDateTime consumeStartTime;   // 消费开始时间
    private LocalDateTime consumeEndTime;     // 消费结束时间

    // 计算延迟
    public long getBrokerLatency() {
        return Duration.between(sendTime, brokerReceiveTime).toMillis();
    }

    public long getConsumerLatency() {
        return Duration.between(brokerReceiveTime, consumeStartTime).toMillis();
    }

    public long getProcessingTime() {
        return Duration.between(consumeStartTime, consumeEndTime).toMillis();
    }

    public long getTotalLatency() {
        return Duration.between(sendTime, consumeEndTime).toMillis();
    }
}

// 5. 查询接口
// 按 TraceId 查询
public MessageTrace getTrace(String traceId);

// 按 OrderId 查询
public List<MessageTrace> getTracesByOrderId(String orderId);

// 查询慢消息（延迟超过阈值）
public List<MessageTrace> getSlowMessages(long thresholdMs);

// 查询失败消息
public List<MessageTrace> getFailedMessages();

// 6. 统计信息
public String getStats() {
    return String.format("""
        📊 消息轨迹统计
        - 总消息数: %d
        - 成功数: %d
        - 失败数: %d
        - 平均延迟: %d ms
        - 慢消息数(>1000ms): %d
        """, totalCount, successCount, failureCount, avgLatency, slowCount);
}
```

### 处理模式设计
```java
public enum ProcessingMode {
    FAST(50),        // 快速处理：50ms
    NORMAL(200),     // 正常处理：200ms
    SLOW(1000),      // 慢处理：1000ms
    VERY_SLOW(3000), // 超慢处理：3000ms
    RANDOM_FAIL(100); // 随机失败：50%概率

    private final long processingTimeMs;
}
```

### 测试场景
1. 发送不同模式的消息，验证轨迹记录
2. 查询 TraceId，验证完整链路
3. 查询慢消息，验证性能分析
4. 查询失败消息，验证错误追踪
5. 压力测试，验证性能开销

---

## Level 12: 多机房容灾与消息同步 ⭐⭐⭐⭐⭐⭐

### 问题场景
主机房故障，如何快速切换到备机房，保证消息不丢失？

### 核心挑战
1. 主备机房消息同步
2. 主备切换
3. 消息去重与幂等
4. 脑裂问题

### Buggy 版本问题
```java
// Bug 1: 主备切换时消息丢失
// 主机房故障，备机房没有同步最新消息

// Bug 2: 消息重复同步
// 主备同步时，消息被重复发送

// Bug 3: 切换过程中消息乱序
// 主备切换时，消息顺序被打乱

// Bug 4: 脑裂问题
// 主备同时工作，导致消息重复处理
```

### 解决方案
```java
// 1. 使用 Dledger 实现主备同步
// Broker 配置
enableDLegerCommitLog=true
dLegerGroup=broker-group
dLegerPeers=n0-127.0.0.1:40911;n1-127.0.0.2:40911;n2-127.0.0.3:40911
dLegerSelfId=n0

// 2. 主备切换
// 自动选举新的 Leader
// 客户端自动切换到新的 Leader

// 3. 消息去重
// 使用全局唯一的 MessageId
String messageId = generateGlobalUniqueId();
Message message = provider.newMessageBuilder()
    .setKeys(messageId)
    .setBody(body)
    .build();

// 消费者去重
if (messageDeduplicationService.isDuplicate(messageId)) {
    log.warn("重复消息，跳过: {}", messageId);
    return ConsumeResult.SUCCESS;
}

// 4. 防止脑裂
// 使用 Raft 协议保证只有一个 Leader
// 客户端只连接 Leader
```

### 架构设计
```
主机房（北京）                备机房（上海）
┌─────────────┐              ┌─────────────┐
│   Broker1   │◄────同步────►│   Broker2   │
│   (Leader)  │              │  (Follower) │
└─────────────┘              └─────────────┘
       ↑                            ↑
       │                            │
   Producer                    Producer
   Consumer                    Consumer

故障切换：
1. Broker1 故障
2. Broker2 自动选举为 Leader
3. Producer/Consumer 自动切换到 Broker2
```

### 测试场景
1. 正常情况，验证主备同步
2. 主机房故障，验证自动切换
3. 切换过程中发送消息，验证不丢失
4. 验证消息去重
5. 模拟脑裂，验证防护机制

---

## 🎯 Level 7-12 总结

### 难度递增
```
Level 7: 延时消息 → 单一高级特性
Level 8: 消息过滤 → 单一高级特性
Level 9: 死信队列 → 异常处理机制
Level 10: 批量处理与流控 → 性能优化
Level 11: 轨迹追踪与可观测性 → 监控与排查
Level 12: 多机房容灾 → 高可用架构
```

### 知识体系
```
基础层 (Level 1-3)
├── 资源管理
├── 重试机制
└── 幂等性

进阶层 (Level 4-6)
├── 性能优化
├── 顺序消息
└── 事务消息

高级层 (Level 7-9)
├── 延时消息
├── 消息过滤
└── 死信队列

架构层 (Level 10-12)
├── 批量处理与流控
├── 轨迹追踪与可观测性
└── 多机房容灾
```

### 实战价值
- Level 1-6: 解决 80% 的生产问题
- Level 7-9: 解决 15% 的复杂场景
- Level 10-12: 解决 5% 的架构级问题

### 学习路径
1. **初级** (Level 1-3): 1-2 周
2. **中级** (Level 4-6): 2-3 周
3. **高级** (Level 7-9): 3-4 周
4. **架构** (Level 10-12): 4-6 周

---

## 📚 扩展方向

### 性能优化专题
- 批量发送优化
- 零拷贝技术
- 内存管理优化
- 网络调优

### 监控运维专题
- Prometheus + Grafana
- 日志分析与告警
- 性能调优实战
- 故障排查手册

### 架构设计专题
- 消息中间件选型
- 混合云架构
- 成本优化
- 安全加固

准备好迎接终极挑战了吗？🚀
