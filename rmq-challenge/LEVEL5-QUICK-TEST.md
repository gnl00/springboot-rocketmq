# Level 5 快速测试指南

## 🎯 测试目标

验证消息顺序性问题及解决方案：
- **Buggy 版本**：并发消费导致消息乱序，状态转换混乱
- **Fixed 版本**：顺序消息保证状态正确流转

---

## 🚀 快速开始

### 1. 启动应用
```bash
cd sb-mq-producer
mvn spring-boot:run
```

### 2. 查看帮助
```bash
curl "http://localhost:8070/challenge/level5/help"
```

---

## 📋 测试场景

### 场景 A：单订单测试（观察乱序问题）

#### 步骤 1：发送订单状态流转消息

```bash
# 发送单个订单的状态流转消息
curl "http://localhost:8070/challenge/level5/simulateOrderFlow?orderId=ORDER-001"

# 返回：✅ 订单 ORDER-001 状态流转消息已发送
```

#### 步骤 2：观察消费者日志

```log
📥 收到订单状态变更消息 - OrderStatusEvent{orderId='ORDER-001', status=CREATED, seq=1}, Thread: ConsumeMessageThread_1
✅ 订单 ORDER-001 状态更新成功: 订单创建 (seq=1)

📥 收到订单状态变更消息 - OrderStatusEvent{orderId='ORDER-001', status=SHIPPED, seq=3}, Thread: ConsumeMessageThread_2
❌ 订单 ORDER-001 状态转换非法！已支付 -> 已发货
⚠️ 订单状态更新失败

📥 收到订单状态变更消息 - OrderStatusEvent{orderId='ORDER-001', status=PAID, seq=2}, Thread: ConsumeMessageThread_3
⚠️ 订单 ORDER-001 收到乱序消息！当前序列号: 3, 收到序列号: 2
```

**问题现象：**
- 消息 3（发货）在消息 2（支付）之前到达
- 不同线程并发处理，无法保证顺序
- 状态转换失败，产生错误

#### 步骤 3：查询订单状态

```bash
curl "http://localhost:8070/challenge/level5/checkOrderStatus?orderId=ORDER-001"
```

返回：
```
📊 订单状态查询

订单 ORDER-001 - 当前状态: 已发货, 成功: 3, 错误: 1, 最后序列号: 3

状态说明：
- 正常流程: 创建 → 支付 → 发货 → 完成
- 当前状态: 已发货
- 如果错误数 > 0，说明出现了乱序或非法状态转换
```

**分析：**
- 错误数 > 0，说明出现了乱序
- 最终状态可能不是预期的"已完成"

---

### 场景 B：多订单并发测试（加剧乱序）

#### 步骤 1：并发发送多个订单

```bash
# 并发发送 5 个订单的状态流转消息
curl "http://localhost:8070/challenge/level5/simulateMultipleOrders?count=5"

# 返回：✅ 已发送 5 个订单的状态流转消息
```

#### 步骤 2：观察消费者日志

```log
📥 收到订单状态变更消息 - OrderStatusEvent{orderId='ORDER-001', status=CREATED, seq=1}, Thread: ConsumeMessageThread_1
📥 收到订单状态变更消息 - OrderStatusEvent{orderId='ORDER-002', status=PAID, seq=2}, Thread: ConsumeMessageThread_2
📥 收到订单状态变更消息 - OrderStatusEvent{orderId='ORDER-001', status=SHIPPED, seq=3}, Thread: ConsumeMessageThread_3
📥 收到订单状态变更消息 - OrderStatusEvent{orderId='ORDER-003', status=CREATED, seq=1}, Thread: ConsumeMessageThread_4
❌ 订单 ORDER-002 状态转换非法！null -> 已支付
⚠️ 订单 ORDER-001 收到乱序消息！
...
```

**问题现象：**
- 多个订单的消息混在一起
- 线程调度导致严重的乱序
- 大量错误和失败

#### 步骤 3：查询所有订单状态

```bash
for i in {1..5}; do
  orderId=$(printf "ORDER-%03d" $i)
  curl "http://localhost:8070/challenge/level5/checkOrderStatus?orderId=$orderId"
  echo ""
done
```

**预期结果：**
- 大部分订单都有错误数 > 0
- 状态混乱，不符合预期流程

---

### 场景 C：重置测试环境

```bash
# 重置所有订单状态
curl "http://localhost:8070/challenge/level5/reset"

# 或重置单个订单
curl "http://localhost:8070/challenge/level5/reset?orderId=ORDER-001"
```

---

## 🔍 问题根源分析

### 1. 生产者问题

**代码片段：**
```java
// Level5ProducerBuggy.java
Message message = provider.newMessageBuilder()
    .setTopic(TOPIC)
    .setTag("status-change")
    .setKeys(orderId)  // 虽然设置了 Key，但不影响队列选择
    .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
    .build();

SendReceipt receipt = producer.send(message);  // 随机选择队列
```

**问题：**
- 使用普通消息发送
- RocketMQ 会**随机选择**消息队列
- 同一订单的消息可能进入不同队列

### 2. 消费者问题

**代码片段：**
```java
@RocketMQMessageListener(
    topic = "order-status-topic",
    consumerGroup = "order-status-consumer-buggy",
    consumptionThreadCount = 5  // 多线程并发消费
    // consumeMode = ConsumeMode.CONCURRENTLY  // 默认并发模式
)
```

**问题：**
- 使用**并发消费模式**（默认）
- 多个线程同时消费不同队列的消息
- 即使同一队列，也可能被不同线程处理

### 3. 乱序原因示意图

```
生产者发送：
ORDER-001: [创建(seq=1)] → [支付(seq=2)] → [发货(seq=3)] → [完成(seq=4)]
            ↓               ↓               ↓               ↓
         Queue0          Queue1          Queue0          Queue1  (随机分配)

消费者消费：
Thread-1: 处理 Queue0 的消息（创建、发货）
Thread-2: 处理 Queue1 的消息（支付、完成）

实际处理顺序：
Thread-1: 创建(seq=1) ✅
Thread-2: 支付(seq=2) ✅
Thread-1: 发货(seq=3) ✅
Thread-2: 完成(seq=4) ✅  (看起来正常)

但如果 Thread-1 处理慢：
Thread-2: 支付(seq=2) ✅
Thread-1: 创建(seq=1) ❌ (乱序！)
Thread-2: 完成(seq=4) ❌ (跳过了发货)
Thread-1: 发货(seq=3) ❌ (已经完成了)
```

---

## 💡 解决方案思路

### 核心原理

RocketMQ 的顺序消息保证：
1. **同一队列内的消息**是有序的（FIFO）
2. **顺序消费模式**保证同一队列的消息被同一线程顺序处理

### 解决方案要点

#### 1. 生产者：使用 MessageQueueSelector

```java
// 伪代码示例
producer.send(message, new MessageQueueSelector() {
    @Override
    public MessageQueue select(List<MessageQueue> mqs, Message msg, Object arg) {
        // arg 是 orderId
        // 同一 orderId 总是选择同一个队列
        int index = arg.hashCode() % mqs.size();
        return mqs.get(index);
    }
}, orderId);  // orderId 作为分区键
```

**关键点：**
- 同一 `orderId` 通过 hash 选择同一队列
- 保证同一订单的消息进入同一队列

#### 2. 消费者：使用顺序消费模式

```java
// 伪代码示例
@RocketMQMessageListener(
    topic = "order-status-topic",
    consumerGroup = "order-status-consumer-fixed",
    consumeMode = ConsumeMode.ORDERLY  // 顺序消费模式
)
```

**关键点：**
- 顺序消费模式保证同一队列的消息被同一线程顺序处理
- 处理完一条才会处理下一条

### 3. 完整流程

```
生产者：
ORDER-001: 所有消息 → Queue0 (通过 orderId hash 选择)
ORDER-002: 所有消息 → Queue1
ORDER-003: 所有消息 → Queue2

消费者（顺序模式）：
Thread-1 锁定 Queue0: 创建 → 支付 → 发货 → 完成 (顺序处理)
Thread-2 锁定 Queue1: ...
Thread-3 锁定 Queue2: ...
```

---

## 🎯 挑战任务

### 任务 1：分析问题
1. 运行 Buggy 版本测试
2. 观察日志中的乱序现象
3. 理解为什么会乱序

### 任务 2：实现修复

创建以下文件：
1. `Level5ProducerFixed.java` - 使用 MessageQueueSelector
2. `Level5ConsumerFixed.java` - 使用顺序消费模式

关键修改点：
- 生产者：指定队列选择策略
- 消费者：修改 `consumeMode`

### 任务 3：验证修复

1. 测试单订单流转：
   ```bash
   curl "http://localhost:8070/challenge/level5-fixed/simulateOrderFlow?orderId=ORDER-001"
   curl "http://localhost:8070/challenge/level5/checkOrderStatus?orderId=ORDER-001"
   ```

2. 验证结果：
   - 错误数 = 0
   - 最终状态 = 已完成
   - 日志无乱序警告

3. 并发测试：
   ```bash
   curl "http://localhost:8070/challenge/level5-fixed/simulateMultipleOrders?count=10"
   ```

4. 验证所有订单状态都正常

---

## ✅ 验收标准

**Buggy 版本：**
- ❌ 单订单测试：错误数 > 0
- ❌ 并发测试：大量乱序和错误
- ❌ 日志中有 ⚠️ 和 ❌ 警告

**Fixed 版本：**
- ✅ 单订单测试：错误数 = 0，状态正确流转
- ✅ 并发测试：所有订单状态正确
- ✅ 日志中无乱序警告
- ✅ 最终状态符合预期（已完成）

---

## 📚 扩展学习

### 1. 顺序消息的代价

**优点：**
- 保证消息顺序
- 业务逻辑正确

**缺点：**
- 吞吐量下降（单线程处理）
- 如果一条消息阻塞，后续消息都会延迟
- 队列数量限制并发度

### 2. 生产环境最佳实践

1. **按需使用顺序消息**
   - 只对必须有序的场景使用
   - 其他场景用普通消息（吞吐量更高）

2. **合理设置队列数量**
   - 队列数量 = 最大并发度
   - 建议：4-16 个队列

3. **分区键选择**
   - 选择合适的分区键（如 orderId, userId）
   - 避免热点分区（某个队列消息过多）

4. **监控和告警**
   - 监控消费延迟
   - 单个队列堆积告警

### 3. 常见问题

**Q: 全局有序 vs 分区有序？**
- 全局有序：整个 Topic 只有 1 个队列（吞吐量极低）
- 分区有序：每个分区内有序（推荐）

**Q: 顺序消息失败如何处理？**
- 顺序消费模式会自动重试
- 如果一直失败，会阻塞后续消息
- 建议：设置最大重试次数，失败后跳过或人工处理

**Q: 如何提高顺序消息吞吐量？**
- 增加队列数量
- 优化消费逻辑（减少处理时间）
- 水平扩展消费者实例

---

**开始你的挑战吧！** 🚀

目标：实现**零错误**的顺序消息处理！
