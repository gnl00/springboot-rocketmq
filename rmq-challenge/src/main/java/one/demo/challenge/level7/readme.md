# Level 7: 延时消息与定时任务

## 🎯 挑战难度：⭐⭐⭐⭐

## 📖 问题场景

### 业务背景
电商系统中，用户下单后需要在 **30 分钟内完成支付**，否则订单自动取消并恢复库存。

### 业务流程
```
1. 用户下单 → 创建订单（状态：待支付）
2. 扣减库存
3. 发送延时消息（30分钟后执行）
4. 30分钟后：
   - 如果订单未支付 → 取消订单，恢复库存
   - 如果订单已支付 → 忽略延时消息
```

---

## ❌ Buggy 版本的问题

### 问题 1：延时消息发送失败 → 僵尸订单

```java
// 创建订单
orderService.createOrder(order);

// 发送延时消息
sendDelayMessage(orderId); // ❌ 如果这里失败，订单永远不会被取消
```

**Bug 现象：**
- 订单已创建，库存已扣减
- 但延时消息发送失败
- 30 分钟后订单不会被自动取消
- 形成"僵尸订单"，永久占用库存

---

### 问题 2：用户支付后，延时消息仍然执行

```java
// 发送延时消息
sendDelayMessage(orderId);

// 用户 10 分钟后支付
orderService.payOrder(orderId);

// 30 分钟后，延时消息执行
// ❌ 消费者没有检查订单状态，直接取消订单
orderService.cancelOrder(orderId);
```

**Bug 现象：**
- 用户已支付订单
- 但延时消息仍然执行
- 订单被错误取消
- 用户投诉：钱付了，订单却被取消了

---

### 问题 3：延时时间不精确

```java
// RocketMQ 只支持 18 个固定的延时等级
// 1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h

// 需求：30 分钟
// 只能选择：20m（不够）或 30m（太长）
message.setDeliveryTimestamp(System.currentTimeMillis() + 30 * 60 * 1000);
```

**Bug 现象：**
- 无法精确设置 30 分钟
- 只能选择 20 分钟或 30 分钟
- 业务需求无法满足

---

### 问题 4：重复消费导致库存多次恢复

```java
// 消费者没有幂等性保证
@Override
public ConsumeResult consume(MessageView messageView) {
    // ❌ 直接取消订单，没有检查是否已处理
    orderService.cancelOrder(orderId);
    return ConsumeResult.SUCCESS;
}
```

**Bug 现象：**
- 延时消息被重复消费
- 订单被多次取消
- 库存被多次恢复（100 → 105 → 110）

---

## 🧪 测试场景

### 场景 1：正常流程 - 订单超时取消

```bash
# 1. 重置数据
curl "http://localhost:8070/challenge/level7/reset"

# 2. 创建订单
curl "http://localhost:8070/challenge/level7/createOrder?version=buggy"
# 返回：OrderId: ORDER-xxx

# 3. 查看初始状态
curl "http://localhost:8070/challenge/level7/checkAll"
# 订单状态：待支付
# 库存：PRODUCT-001=95（已扣减5）

# 4. 等待 30 秒（延时消息执行）
sleep 30

# 5. 再次查看状态
curl "http://localhost:8070/challenge/level7/checkAll"
# 订单状态：已取消
# 库存：PRODUCT-001=100（已恢复）
```

---

### 场景 2：Bug - 用户支付后订单被错误取消

```bash
# 1. 重置数据
curl "http://localhost:8070/challenge/level7/reset"

# 2. 创建订单
curl "http://localhost:8070/challenge/level7/createOrder?version=buggy"
# 返回：OrderId: ORDER-abc123

# 3. 10 秒后支付订单
sleep 10
curl "http://localhost:8070/challenge/level7/payOrder?orderId=ORDER-abc123"
# 返回：支付成功

# 4. 查看订单状态
curl "http://localhost:8070/challenge/level7/checkOrder?orderId=ORDER-abc123"
# 订单状态：已支付 ✅

# 5. 再等待 20 秒（延时消息执行）
sleep 20

# 6. 再次查看订单状态
curl "http://localhost:8070/challenge/level7/checkOrder?orderId=ORDER-abc123"
# ❌ Bug 现象：订单状态变成"已取消"，但应该是"已支付"
```

---

### 场景 3：Bug - 延时消息发送失败

```bash
# 1. 重置数据
curl "http://localhost:8070/challenge/level7/reset"

# 2. 模拟延时消息发送失败
curl "http://localhost:8070/challenge/level7/buggy/simulateDelayMessageFailure?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

# 3. 查看订单状态
curl "http://localhost:8070/challenge/level7/checkAll"
# 订单状态：待支付
# 库存：PRODUCT-001=95（已扣减）

# 4. 等待 30 秒
sleep 30

# 5. 再次查看状态
curl "http://localhost:8070/challenge/level7/checkAll"
# ❌ Bug 现象：订单仍然是"待支付"，永远不会被取消
# 库存：PRODUCT-001=95（永久占用）
```

---

## 💡 核心问题分析

### 问题本质

**延时消息一旦发送，就无法取消！**

```
时间线：
0分钟：创建订单，发送延时消息（30分钟后执行）
10分钟：用户支付订单
30分钟：延时消息执行 ❌ 无法阻止
```

### RocketMQ 延时消息的限制

1. **固定的延时等级**：只支持 18 个固定等级，无法自定义
2. **无法取消**：消息一旦发送，无法撤回
3. **无法修改**：无法修改延时时间

---

## 🎯 挑战任务

### 你的任务

1. ✅ 运行 Buggy 版本，观察问题现象
2. ✅ 理解为什么会出现这些问题
3. 🔧 **设计并实现 Fixed 版本**
4. 🔧 解决以下问题：
   - 如何处理延时消息发送失败？
   - 如何在用户支付后"取消"延时消息？
   - 如何实现精确的延时时间？
   - 如何保证幂等性？

---

## 💭 解决方案提示

### 方案 1：定时扫描数据库

```java
@Scheduled(cron = "0 * * * * ?") // 每分钟执行
public void cancelExpiredOrders() {
    List<Order> expiredOrders = orderService.findExpiredOrders();
    for (Order order : expiredOrders) {
        if (order.getStatus() == OrderStatus.PENDING) {
            cancelOrder(order);
        }
    }
}
```

**优点：**
- 可以精确控制延时时间
- 可以"取消"（修改订单状态即可）
- 实现简单

**缺点：**
- 数据库压力大
- 实时性差（最多延迟 1 分钟）
- 分布式环境需要分布式锁

---

### 方案 2：时间轮算法

```java
HashedWheelTimer timer = new HashedWheelTimer(1, TimeUnit.SECONDS, 60);

// 添加延时任务
Timeout timeout = timer.newTimeout(task -> {
    cancelOrder(orderId);
}, 30, TimeUnit.MINUTES);

// 用户支付后，取消任务
timeout.cancel();
```

**优点：**
- 精确控制延时时间
- 可以取消任务
- 性能好（O(1) 时间复杂度）

**缺点：**
- 内存占用（任务数量大时）
- 单机方案（需要配合分布式锁）
- 任务不持久化（宕机丢失）

---

### 方案 3：RocketMQ + 消费者检查（推荐）

```java
// 生产者：正常发送延时消息
sendDelayMessage(orderId);

// 消费者：检查订单状态
@Override
public ConsumeResult consume(MessageView messageView) {
    Order order = orderService.getOrder(orderId);

    // 关键：检查订单状态
    if (order.getStatus() != OrderStatus.PENDING) {
        log.info("订单状态不是待支付，跳过取消");
        return ConsumeResult.SUCCESS;
    }

    // 只有待支付的订单才取消
    orderService.cancelOrder(orderId);
    return ConsumeResult.SUCCESS;
}
```

**优点：**
- 实现简单
- 消息持久化
- 分布式友好

**缺点：**
- 延时时间不精确（只能选择固定等级）
- 消息仍然会被消费（只是不执行取消操作）

---

### 方案 4：RocketMQ + 时间轮（生产推荐）

结合方案 2 和方案 3 的优点：
- 使用 RocketMQ 持久化消息
- 使用时间轮管理任务
- 支持取消任务

---

## 📊 方案对比

| 方案 | 精确度 | 可取消 | 性能 | 复杂度 | 推荐度 |
|------|--------|--------|------|--------|--------|
| RocketMQ 延时等级 | 低 | ❌ | 高 | 低 | ⭐⭐⭐ |
| 定时扫描数据库 | 中 | ✅ | 低 | 低 | ⭐⭐ |
| 时间轮算法 | 高 | ✅ | 高 | 中 | ⭐⭐⭐⭐ |
| RocketMQ + 检查 | 低 | ✅ | 高 | 低 | ⭐⭐⭐⭐ |
| RocketMQ + 时间轮 | 高 | ✅ | 高 | 高 | ⭐⭐⭐⭐⭐ |

---

## 🚀 开始你的挑战

### 实现 Fixed 版本

创建以下文件：
- `Level7ProducerFixed.java` - 你的解决方案
- `Level7ConsumerFixed.java` - 改进的消费者

### 验收标准

1. ✅ 延时消息发送失败时，有兜底方案
2. ✅ 用户支付后，订单不会被错误取消
3. ✅ 延时时间尽可能精确
4. ✅ 保证幂等性，重复消费不会导致问题
5. ✅ 通过所有测试场景

---

## 📚 参考资料

- [RocketMQ 延时消息官方文档](https://rocketmq.apache.org/docs/featureBehavior/02delaymessage)
- [Netty HashedWheelTimer](https://netty.io/4.1/api/io/netty/util/HashedWheelTimer.html)
- [Kafka 时间轮算法](https://www.confluent.io/blog/apache-kafka-purgatory-hierarchical-timing-wheels/)

---

**准备好了吗？开始实现你的 Fixed 版本吧！** 💪🚀

如果需要提示，可以向我请求 Best 版本的实现！
