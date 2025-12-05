# Level 6: 事务消息问题 - 快速测试指南

## 🎯 测试目标

验证 RocketMQ 事务消息如何解决**本地事务与消息发送一致性**问题。

## 📋 前置准备

1. 确保 RocketMQ 服务已启动
2. 启动 rmq-challenge 应用

```bash
cd /Volumes/devdata/workspace/code/sb-rmq/rmq-challenge
mvn spring-boot:run
```

## 🧪 测试场景

### 场景 1: Buggy 版本 - 消息发送失败导致数据不一致

**问题描述：** 订单创建成功，但消息发送失败，导致库存和积分未变化。

```bash
# 1. 重置数据
curl "http://localhost:8070/challenge/level6/reset"

# 2. 查看初始状态
curl "http://localhost:8070/challenge/level6/checkAll"
# 预期：订单=0, 库存=PRODUCT-001:100, 积分=USER-001:0

# 3. 模拟消息发送失败
curl "http://localhost:8070/challenge/level6/buggy/simulateMessageFailure?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"
```

**预期结果：**
```
❌ Bug 现象：
- 订单已创建（ORDER-xxx）
- 库存未扣减（仍然是 100）
- 积分未增加（仍然是 0）
→ 数据不一致！
```

---

### 场景 2: Buggy 版本 - 订单创建失败导致数据不一致

**问题描述：** 消息发送成功，但订单创建失败，导致库存和积分已变化，但订单不存在。

```bash
# 1. 重置数据
curl "http://localhost:8070/challenge/level6/reset"

# 2. 模拟订单创建失败
curl "http://localhost:8070/challenge/level6/buggy/simulateOrderFailure?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

# 3. 检查数据一致性
curl "http://localhost:8070/challenge/level6/checkAll"
```

**预期结果：**
```
❌ Bug 现象：
- 订单不存在
- 库存已扣减（100 → 95）
- 积分已增加（0 → 10）
→ 数据不一致！
```

---

### 场景 3: Buggy 版本 - 三种错误方案对比

```bash
# 重置数据
curl "http://localhost:8070/challenge/level6/reset"

# 方案1：先创建订单，再发送消息
curl "http://localhost:8070/challenge/level6/buggy/createOrder1?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

# 方案2：先发送消息，再创建订单
curl "http://localhost:8070/challenge/level6/buggy/createOrder2?userId=USER-002&productId=PRODUCT-002&quantity=3&amount=50.00"

# 方案3：使用try-catch回滚
curl "http://localhost:8070/challenge/level6/buggy/createOrder3?userId=USER-003&productId=PRODUCT-003&quantity=2&amount=30.00"

# 检查结果
curl "http://localhost:8070/challenge/level6/checkAll"
```

**思考：** 为什么这三种方案都无法保证一致性？

---

### 场景 4: Fixed 版本 - 正常流程（事务消息）

**解决方案：** 使用 RocketMQ 事务消息保证一致性。

```bash
# 1. 重置数据
curl "http://localhost:8070/challenge/level6/reset"

# 2. 查看初始状态
curl "http://localhost:8070/challenge/level6/checkAll"

# 3. 使用事务消息创建订单
curl "http://localhost:8070/challenge/level6/fixed/createOrder?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

# 4. 等待消息被消费（2秒）
sleep 2

# 5. 检查数据一致性
curl "http://localhost:8070/challenge/level6/checkAll"
```

**预期结果：**
```
✅ 正常流程：
- 订单已创建（ORDER-xxx, 状态=CONFIRMED）
- 库存已扣减（100 → 95）
- 积分已增加（0 → 10）
→ 数据一致！
```

---

### 场景 5: Fixed 版本 - 本地事务失败（事务回滚）

**测试目标：** 验证本地事务失败时，消息不会被发送。

```bash
# 1. 重置数据
curl "http://localhost:8070/challenge/level6/reset"

# 2. 模拟本地事务失败
curl "http://localhost:8070/challenge/level6/fixed/simulateLocalTransactionFailure?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

# 3. 等待 2 秒
sleep 2

# 4. 检查数据一致性
curl "http://localhost:8070/challenge/level6/checkAll"
```

**预期结果：**
```
✅ 事务回滚：
- 订单不存在
- 库存未扣减（仍然是 100）
- 积分未增加（仍然是 0）
→ 数据一致！
```

---

### 场景 6: 压力测试 - 并发创建订单

```bash
# 重置数据
curl "http://localhost:8070/challenge/level6/reset"

# 并发创建 10 个订单
for i in {1..10}; do
  curl "http://localhost:8070/challenge/level6/fixed/createOrder?userId=USER-001&productId=PRODUCT-001&quantity=1&amount=10.00" &
done
wait

# 等待消息被消费
sleep 3

# 检查数据一致性
curl "http://localhost:8070/challenge/level6/checkAll"
```

**预期结果：**
- 10 个订单全部创建成功
- 库存扣减 10（100 → 90）
- 积分增加 10（0 → 10）

---

## 📊 对比总结

| 方案 | 一致性 | 性能 | 复杂度 | 推荐度 |
|------|--------|------|--------|--------|
| Buggy - 方案1 | ❌ | 高 | 低 | ⭐ |
| Buggy - 方案2 | ❌ | 高 | 低 | ⭐ |
| Buggy - 方案3 | ❌ | 中 | 中 | ⭐⭐ |
| Fixed - 事务消息 | ✅ | 中 | 中 | ⭐⭐⭐⭐⭐ |

## 🔍 关键观察点

### 1. Buggy 版本的日志

```
✅ [方案1] 订单创建成功 - OrderId: ORDER-xxx
❌ 模拟消息发送失败 - OrderId: ORDER-xxx
```

### 2. Fixed 版本的日志

```
🚀 [事务消息] 开始发送 - OrderId: ORDER-xxx
📤 [事务消息] Half 消息已发送 - MessageId: xxx
✅ [本地事务] 订单创建成功 - OrderId: ORDER-xxx
✅ [事务消息] 事务已提交 - OrderId: ORDER-xxx
📥 收到订单事件 - OrderId: ORDER-xxx
📦 [库存] 扣减成功 - ProductId: PRODUCT-001, Quantity: 5
⭐ [积分] 增加成功 - UserId: USER-001, Points: +10
```

### 3. 事务回滚的日志

```
📤 [事务消息] Half 消息已发送 - MessageId: xxx
❌ [模拟] 本地事务失败 - OrderId: ORDER-xxx
❌ [事务消息] 事务已回滚 - OrderId: ORDER-xxx
```

## 💡 核心知识点

### 1. 事务消息工作流程

```
Producer                    Broker                    Consumer
   │                          │                          │
   │ 1. 发送 Half 消息        │                          │
   ├─────────────────────────>│                          │
   │                          │ (消息对消费者不可见)      │
   │                          │                          │
   │ 2. 执行本地事务          │                          │
   │    (创建订单)            │                          │
   │                          │                          │
   │ 3. Commit/Rollback       │                          │
   ├─────────────────────────>│                          │
   │                          │ 4. 消息对消费者可见      │
   │                          ├─────────────────────────>│
```

### 2. 为什么事务消息能保证一致性？

- **Half 消息**：消息先发送，但对消费者不可见
- **本地事务绑定**：本地事务成功 → Commit，失败 → Rollback
- **事务回查**：防止网络问题导致的状态不确定

### 3. 事务消息的限制

- 性能略低于普通消息
- 不支持延时消息
- 回查次数有限制（默认 15 次）

## 🎓 思考题

1. 为什么先创建订单再发送消息会有问题？
2. 为什么先发送消息再创建订单会有问题？
3. 为什么 try-catch 回滚无法保证原子性？
4. 事务消息如何保证最终一致性？
5. 如果下游服务处理失败，如何回滚？
6. 如何在生产环境中持久化事务状态？

## 🏆 挑战完成标准

- ✅ 理解事务消息的工作流程
- ✅ 能够解释为什么普通消息无法保证一致性
- ✅ 能够实现事务消息的发送和回查
- ✅ 能够处理各种异常场景
- ✅ 理解事务消息的适用场景和限制

恭喜你完成 Level 6 挑战！🎉
