# Level 7: 延时消息与定时任务

## 🎯 挑战难度：⭐⭐⭐⭐

## 📖 问题场景

### 业务背景
电商系统中，用户下单后需要在 30 分钟内完成支付，否则订单自动取消。

### 业务流程
```
1. 用户下单 → 创建订单（状态：待支付）
2. 发送延时消息（30分钟后执行）
3. 30分钟后：
   - 如果订单未支付 → 取消订单，恢复库存
   - 如果订单已支付 → 忽略延时消息
```

### 核心挑战
1. 如何实现精确的延时时间？
2. 如何取消已发送的延时消息？
3. 如何保证延时消息的幂等性？
4. 如何处理延时消息发送失败？

---

## ❌ Buggy 版本的问题

### 问题 1：延时等级限制
```java
// Bug: RocketMQ 只支持 18 个固定的延时等级
// 1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h

// 需求：30 分钟后取消订单
// 问题：只能选择 20m 或 30m，无法精确到 30 分钟
```

### 问题 2：无法取消延时消息
```java
// Bug: 用户在 10 分钟后支付了订单
// 但是延时消息已经发送，无法取消
// 30 分钟后，订单被错误取消
```

### 问题 3：延时消息发送失败
```java
// Bug: 延时消息发送失败，订单永远不会被取消
// 导致大量"僵尸订单"占用库存
```

### 问题 4：延时消息重复消费
```java
// Bug: 延时消息被重复消费
// 订单被多次取消，库存被多次恢复
```

---

## ✅ 解决方案设计

### 方案 1：使用 RocketMQ 延时等级（简单场景）

**适用场景：** 延时时间可以匹配固定等级

```java
// 延时等级：1-18
// 1=1s, 2=5s, 3=10s, ..., 16=30m, 17=1h, 18=2h

Message message = provider.newMessageBuilder()
    .setTopic("order-cancel-topic")
    .setKeys(orderId)
    .setDeliveryTimestamp(System.currentTimeMillis() + 30 * 60 * 1000) // 30分钟
    .build();
```

**优点：**
- 实现简单
- 性能好

**缺点：**
- 只支持 18 个固定等级
- 无法取消

---

### 方案 2：定时扫描数据库（传统方案）

**实现思路：**
```java
// 1. 订单表增加字段：create_time, expire_time
// 2. 定时任务每分钟扫描一次
// 3. 查询 expire_time < now() AND status = '待支付' 的订单
// 4. 取消订单，恢复库存

@Scheduled(cron = "0 * * * * ?") // 每分钟执行
public void cancelExpiredOrders() {
    List<Order> expiredOrders = orderService.findExpiredOrders();
    for (Order order : expiredOrders) {
        cancelOrder(order);
    }
}
```

**优点：**
- 可以精确控制延时时间
- 可以取消（修改订单状态即可）

**缺点：**
- 数据库压力大
- 实时性差（最多延迟 1 分钟）
- 分布式环境下需要分布式锁

---

### 方案 3：时间轮算法（推荐方案）

**实现思路：**
```java
// 使用 Netty 的 HashedWheelTimer 或自己实现时间轮

HashedWheelTimer timer = new HashedWheelTimer(
    1, TimeUnit.SECONDS,  // tickDuration: 每格 1 秒
    60                     // ticksPerWheel: 60 格（1 分钟一圈）
);

// 添加延时任务
Timeout timeout = timer.newTimeout(task -> {
    cancelOrder(orderId);
}, 30, TimeUnit.MINUTES);

// 取消任务
timeout.cancel();
```

**优点：**
- 精确控制延时时间
- 可以取消任务
- 性能好（O(1) 时间复杂度）

**缺点：**
- 内存占用（任务数量大时）
- 单机方案（需要配合分布式锁）

---

### 方案 4：RocketMQ + 时间轮（生产推荐）

**实现思路：**
```java
// 1. 发送延时消息到 RocketMQ（持久化）
// 2. 消费者使用时间轮管理任务
// 3. 支持取消（发送取消消息）

// 发送延时消息
Message delayMessage = provider.newMessageBuilder()
    .setTopic("order-delay-topic")
    .setKeys(orderId)
    .setDeliveryTimestamp(System.currentTimeMillis() + 30 * 60 * 1000)
    .build();

// 消费者收到消息后，添加到时间轮
timer.newTimeout(task -> {
    // 检查订单状态
    Order order = orderService.getOrder(orderId);
    if (order.getStatus() == OrderStatus.UNPAID) {
        cancelOrder(orderId);
    }
}, 0, TimeUnit.SECONDS);

// 用户支付后，发送取消消息
Message cancelMessage = provider.newMessageBuilder()
    .setTopic("order-cancel-delay-topic")
    .setKeys(orderId)
    .build();
```

**优点：**
- 消息持久化，不怕宕机
- 支持取消
- 分布式友好

**缺点：**
- 实现复杂度较高

---

## 🧪 测试场景

### 场景 1：正常流程 - 订单超时取消

```bash
# 1. 创建订单（待支付）
curl "http://localhost:8070/challenge/level7/createOrder?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"
# 返回：OrderId: ORDER-xxx, 30分钟后自动取消

# 2. 查看订单状态
curl "http://localhost:8070/challenge/level7/checkOrder?orderId=ORDER-xxx"
# 状态：待支付

# 3. 等待 30 分钟（测试环境可以设置为 30 秒）
sleep 30

# 4. 再次查看订单状态
curl "http://localhost:8070/challenge/level7/checkOrder?orderId=ORDER-xxx"
# 状态：已取消，库存已恢复
```

---

### 场景 2：用户支付后，取消延时任务

```bash
# 1. 创建订单
curl "http://localhost:8070/challenge/level7/createOrder?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"
# 返回：OrderId: ORDER-xxx

# 2. 10 秒后，用户支付
sleep 10
curl "http://localhost:8070/challenge/level7/payOrder?orderId=ORDER-xxx"
# 返回：支付成功，延时任务已取消

# 3. 等待 30 秒（超过原定的取消时间）
sleep 30

# 4. 查看订单状态
curl "http://localhost:8070/challenge/level7/checkOrder?orderId=ORDER-xxx"
# 状态：已支付（未被取消）
```

---

### 场景 3：延时消息发送失败

```bash
# 1. 模拟延时消息发送失败
curl "http://localhost:8070/challenge/level7/buggy/createOrderWithFailure?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

# 2. 等待 30 秒
sleep 30

# 3. 查看订单状态
curl "http://localhost:8070/challenge/level7/checkOrder?orderId=ORDER-xxx"
# Bug 现象：订单仍然是"待支付"，永远不会被取消
```

---

### 场景 4：延时消息重复消费

```bash
# 1. 创建订单
curl "http://localhost:8070/challenge/level7/createOrder?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

# 2. 模拟消息重复消费
curl "http://localhost:8070/challenge/level7/simulateDuplicateCancel?orderId=ORDER-xxx&times=3"

# 3. 查看库存
curl "http://localhost:8070/challenge/level6/checkAll"
# Bug 现象：库存被多次恢复（100 → 105 → 110 → 115）
```

---

## 💡 核心知识点

### 1. RocketMQ 延时消息的限制

```java
// 18 个延时等级（不可自定义）
1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h

// 设置延时等级
message.setDelayTimeLevel(16); // 30m

// 或者设置投递时间戳（推荐）
message.setDeliveryTimestamp(System.currentTimeMillis() + 30 * 60 * 1000);
```

### 2. 时间轮算法原理

```
时间轮结构：
┌─────┬─────┬─────┬─────┬─────┬─────┐
│  0  │  1  │  2  │  3  │  4  │  5  │  ... (60格，每格1秒)
└─────┴─────┴─────┴─────┴─────┴─────┘
   ↑
  指针每秒移动一格

添加任务：
- 30秒后执行 → 放入第 30 格
- 90秒后执行 → 放入第 30 格，轮数=1

时间复杂度：
- 添加任务：O(1)
- 取消任务：O(1)
- 执行任务：O(1)
```

### 3. 幂等性保证

```java
// 方案1：检查订单状态
if (order.getStatus() != OrderStatus.UNPAID) {
    log.warn("订单状态不是待支付，跳过取消");
    return;
}

// 方案2：使用分布式锁
String lockKey = "order:cancel:" + orderId;
if (redisLock.tryLock(lockKey)) {
    try {
        cancelOrder(orderId);
    } finally {
        redisLock.unlock(lockKey);
    }
}

// 方案3：数据库乐观锁
UPDATE orders
SET status = 'CANCELLED', version = version + 1
WHERE order_id = ? AND status = 'UNPAID' AND version = ?
```

---

## 🎯 挑战目标

1. ✅ 理解 RocketMQ 延时消息的限制
2. ✅ 实现订单超时自动取消
3. ✅ 实现延时任务的取消机制
4. ✅ 保证延时消息的幂等性
5. ✅ 处理延时消息发送失败的情况
6. 🔧 对比不同方案的优缺点
7. 🔧 设计生产级的延时任务系统

---

## 📊 方案对比

| 方案 | 精确度 | 可取消 | 性能 | 复杂度 | 推荐度 |
|------|--------|--------|------|--------|--------|
| RocketMQ 延时等级 | 低 | ❌ | 高 | 低 | ⭐⭐⭐ |
| 定时扫描数据库 | 中 | ✅ | 低 | 低 | ⭐⭐ |
| 时间轮算法 | 高 | ✅ | 高 | 中 | ⭐⭐⭐⭐ |
| RocketMQ + 时间轮 | 高 | ✅ | 高 | 高 | ⭐⭐⭐⭐⭐ |

---

## 🚀 扩展思考

1. 如何实现任意时间的延时消息？
2. 如何保证延时任务在分布式环境下只执行一次？
3. 如何监控延时任务的执行情况？
4. 如何处理大量延时任务（百万级）？
5. 延时消息 vs 定时任务框架（Quartz、XXL-Job），如何选择？

---

## 📚 参考资料

- [RocketMQ 延时消息官方文档](https://rocketmq.apache.org/docs/featureBehavior/02delaymessage)
- [Netty HashedWheelTimer 源码分析](https://netty.io/4.1/api/io/netty/util/HashedWheelTimer.html)
- [Kafka 时间轮算法](https://www.confluent.io/blog/apache-kafka-purgatory-hierarchical-timing-wheels/)

准备好挑战了吗？🚀
