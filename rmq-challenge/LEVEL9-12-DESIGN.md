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

## Level 10: 消息轨迹与链路追踪 ⭐⭐⭐⭐

### 问题场景
订单系统 → MQ → 库存系统 → MQ → 物流系统，如何追踪消息的完整链路？

### 核心挑战
1. TraceId 的生成和传递
2. 消息轨迹的记录
3. 跨系统的链路追踪
4. 性能开销控制

### Buggy 版本问题
```java
// Bug 1: TraceId 未传递
// 生产者
Message message = builder.setBody(body).build();
// 没有设置 TraceId

// 消费者
// 无法关联上下游

// Bug 2: 消息轨迹数据丢失
// 没有开启消息轨迹功能

// Bug 3: 链路追踪性能开销过大
// 每条消息都记录详细信息，导致性能下降
```

### 解决方案
```java
// 1. 开启消息轨迹
Producer producer = provider.newProducerBuilder()
    .setClientConfiguration(configuration)
    .enableTracing(true) // 开启消息轨迹
    .build();

// 2. TraceId 传递
String traceId = MDC.get("traceId");
if (traceId == null) {
    traceId = UUID.randomUUID().toString();
}

Message message = provider.newMessageBuilder()
    .setTopic("order-topic")
    .addProperty("traceId", traceId) // 传递 TraceId
    .setBody(body)
    .build();

// 3. 消费者提取 TraceId
String traceId = message.getProperty("traceId");
MDC.put("traceId", traceId);
try {
    processMessage(message);
} finally {
    MDC.remove("traceId");
}

// 4. 集成 OpenTelemetry
Span span = tracer.spanBuilder("process-order")
    .setSpanKind(SpanKind.CONSUMER)
    .setAttribute("message.id", message.getMessageId())
    .setAttribute("trace.id", traceId)
    .startSpan();
try (Scope scope = span.makeCurrent()) {
    processMessage(message);
} finally {
    span.end();
}
```

### 测试场景
1. 发送消息，验证 TraceId 生成
2. 消费消息，验证 TraceId 传递
3. 查询消息轨迹，验证完整链路
4. 压力测试，验证性能开销

---

## Level 11: 消息优先级与流控 ⭐⭐⭐⭐⭐

### 问题场景
VIP 订单需要优先处理，但被大量普通订单阻塞。

### 核心挑战
1. 实现消息优先级
2. 防止低优先级消息饿死
3. 消费者流控
4. 下游系统保护

### Buggy 版本问题
```java
// Bug 1: 优先级设置无效
// RocketMQ 不直接支持消息优先级

// Bug 2: 高优先级消息仍然被阻塞
// 所有消息在同一个队列，无法区分优先级

// Bug 3: 流控配置错误
// 没有限制消费速率，下游系统被打垮

// Bug 4: 流控导致消息丢失
// 流控拒绝消息，但没有重试机制
```

### 解决方案
```java
// 方案 1: 多队列优先级调度
// 高优先级 Topic
producer.send(highPriorityTopic, message);

// 低优先级 Topic
producer.send(lowPriorityTopic, message);

// 消费者：优先消费高优先级队列
@Scheduled(fixedRate = 100)
public void consumeMessages() {
    // 先消费高优先级
    List<Message> highPriorityMessages = highPriorityConsumer.poll(10);
    if (!highPriorityMessages.isEmpty()) {
        process(highPriorityMessages);
        return;
    }

    // 再消费低优先级
    List<Message> lowPriorityMessages = lowPriorityConsumer.poll(10);
    process(lowPriorityMessages);
}

// 方案 2: 令牌桶流控
public class RateLimiter {
    private final double rate; // 每秒生成的令牌数
    private double tokens;
    private long lastRefillTime;

    public boolean tryAcquire() {
        refill();
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.currentTimeMillis();
        double elapsed = (now - lastRefillTime) / 1000.0;
        tokens = Math.min(tokens + elapsed * rate, rate);
        lastRefillTime = now;
    }
}

// 使用流控
@Override
public ConsumeResult consume(MessageView message) {
    if (!rateLimiter.tryAcquire()) {
        log.warn("流控限制，稍后重试");
        return ConsumeResult.FAILURE; // 重试
    }

    processMessage(message);
    return ConsumeResult.SUCCESS;
}
```

### 测试场景
1. 发送高低优先级消息，验证优先级
2. 大量低优先级消息，验证高优先级不被阻塞
3. 流控测试，验证消费速率限制
4. 下游系统压力测试

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
Level 10: 链路追踪 → 可观测性
Level 11: 优先级流控 → 性能与资源管理
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
├── 链路追踪
├── 优先级流控
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
