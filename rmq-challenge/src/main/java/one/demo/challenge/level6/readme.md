# Level 6: 事务消息问题

## 🎯 挑战目标

理解并解决分布式系统中的**本地事务与消息发送一致性**问题。

## 📖 问题场景

用户下单后，需要完成三个操作：
1. **创建订单**（本地数据库）
2. **扣减库存**（下游服务，通过MQ通知）
3. **增加积分**（下游服务，通过MQ通知）

这三个操作必须保持一致性：**要么全部成功，要么全部失败**。

## ❌ Buggy 版本的问题

### 问题1：先创建订单，再发送消息

```
1. 创建订单 ✅
2. 发送消息 ❌ (网络异常)
结果：订单已创建，但库存和积分未变化 → 数据不一致
```

### 问题2：先发送消息，再创建订单

```
1. 发送消息 ✅
2. 创建订单 ❌ (数据库异常)
结果：消息已发送，但订单不存在 → 数据不一致
```

### 问题3：使用try-catch回滚

```
1. 创建订单 ✅
2. 发送消息 ❌
3. 回滚订单 ❌ (回滚失败)
结果：无法保证原子性，回滚本身可能失败
```

## ✅ 解决方案：RocketMQ 事务消息

### 事务消息工作流程

```
┌─────────────┐                    ┌─────────────┐                    ┌─────────────┐
│   Producer  │                    │    Broker   │                    │  Consumer   │
└──────┬──────┘                    └──────┬──────┘                    └──────┬──────┘
       │                                  │                                  │
       │ 1. 发送 Half 消息                │                                  │
       ├─────────────────────────────────>│                                  │
       │                                  │ (消息对消费者不可见)              │
       │                                  │                                  │
       │ 2. 执行本地事务                  │                                  │
       │    (创建订单)                    │                                  │
       │                                  │                                  │
       │ 3a. 本地事务成功 → Commit        │                                  │
       ├─────────────────────────────────>│                                  │
       │                                  │ 4. 消息对消费者可见              │
       │                                  ├─────────────────────────────────>│
       │                                  │                                  │
       │ 3b. 本地事务失败 → Rollback      │                                  │
       ├─────────────────────────────────>│                                  │
       │                                  │ (消息被删除，消费者不会收到)      │
       │                                  │                                  │
       │                                  │ 5. 如果长时间未收到确认           │
       │ <─────────────────────────────── │    Broker 回查事务状态           │
       │ 6. 返回事务状态                  │                                  │
       ├─────────────────────────────────>│                                  │
```

### 核心特性

1. **Half 消息**：消息先发送到 Broker，但对消费者不可见
2. **本地事务**：执行本地业务逻辑（创建订单）
3. **Commit/Rollback**：根据本地事务结果，决定消息是否对消费者可见
4. **事务回查**：如果长时间未收到确认，Broker 会主动回查事务状态

## 🧪 测试步骤

### 1. 测试 Buggy 版本

```bash
# 重置数据
curl "http://localhost:8070/challenge/level6/reset"

# 查看初始状态
curl "http://localhost:8070/challenge/level6/checkAll"

# 模拟消息发送失败
curl "http://localhost:8070/challenge/level6/buggy/simulateMessageFailure?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

# 检查数据一致性
curl "http://localhost:8070/challenge/level6/checkAll"
# 预期：订单已创建，但库存和积分未变化（数据不一致）

# 重置数据
curl "http://localhost:8070/challenge/level6/reset"

# 模拟订单创建失败
curl "http://localhost:8070/challenge/level6/buggy/simulateOrderFailure?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

# 检查数据一致性
curl "http://localhost:8070/challenge/level6/checkAll"
# 预期：订单不存在，但库存和积分已变化（数据不一致）
```

### 2. 测试 Fixed 版本

```bash
# 重置数据
curl "http://localhost:8070/challenge/level6/reset"

# 正常创建订单（使用事务消息）
curl "http://localhost:8070/challenge/level6/fixed/createOrder?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

# 检查数据一致性
curl "http://localhost:8070/challenge/level6/checkAll"
# 预期：订单已创建，库存已扣减，积分已增加（数据一致）

# 重置数据
curl "http://localhost:8070/challenge/level6/reset"

# 模拟本地事务失败
curl "http://localhost:8070/challenge/level6/fixed/simulateLocalTransactionFailure?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

# 检查数据一致性
curl "http://localhost:8070/challenge/level6/checkAll"
# 预期：订单不存在，库存和积分未变化（数据一致）
```

## 💡 关键知识点

### 1. 为什么普通消息无法保证一致性？

- **先创建订单，再发送消息**：消息发送失败时，订单已创建
- **先发送消息，再创建订单**：订单创建失败时，消息已发送
- **本地事务和消息发送不是原子操作**

### 2. 事务消息如何保证一致性？

- **Half 消息机制**：消息先发送，但对消费者不可见
- **本地事务与消息绑定**：本地事务成功 → Commit，失败 → Rollback
- **事务回查机制**：防止因网络问题导致的状态不确定

### 3. 事务回查的作用

当 Producer 发送 Commit/Rollback 失败时（网络问题），Broker 会主动回查：

```java
TransactionChecker checker = messageView -> {
    // 查询本地事务状态（从数据库）
    Order order = orderService.getOrder(orderId);

    if (order != null && order.getState() == OrderState.CONFIRMED) {
        return TransactionResolution.COMMIT;  // 订单已创建，提交消息
    } else {
        return TransactionResolution.ROLLBACK; // 订单不存在，回滚消息
    }
};
```

### 4. 事务消息的限制

- **性能开销**：Half 消息 + 回查机制，性能低于普通消息
- **不支持延时消息**：事务消息不能设置延时
- **回查次数限制**：默认回查 15 次，超过后消息会被丢弃

## 🎓 挑战任务

1. ✅ 运行 Buggy 版本，观察数据不一致现象
2. ✅ 理解为什么普通消息无法保证一致性
3. ✅ 学习 RocketMQ 事务消息的工作原理
4. ✅ 运行 Fixed 版本，验证事务消息的效果
5. 🔧 思考：如何在生产环境中实现事务状态持久化？
6. 🔧 思考：如果下游服务处理失败，如何回滚？

## 📚 扩展阅读

- [RocketMQ 事务消息官方文档](https://rocketmq.apache.org/docs/featureBehavior/04transactionmessage)
- 分布式事务解决方案对比：2PC、TCC、Saga、事务消息
- 最终一致性 vs 强一致性

## 🏆 验收标准

- ✅ 理解事务消息的工作流程
- ✅ 能够实现事务消息的发送和回查
- ✅ 能够解释为什么事务消息能保证一致性
- ✅ 能够处理各种异常场景

恭喜你完成 Level 6 挑战！🎉
