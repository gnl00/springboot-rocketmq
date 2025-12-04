# Level 8: 消息过滤与标签路由

## 🎯 挑战难度：⭐⭐⭐

## 📖 问题场景

### 业务背景
电商系统中，不同类型的订单需要不同的处理逻辑：
- **普通订单**：正常流程处理
- **秒杀订单**：高优先级处理，需要特殊库存扣减逻辑
- **预售订单**：延迟发货，需要特殊处理
- **VIP订单**：专属客服跟进，优先配送

### 业务流程
```
订单创建 → 发送MQ消息（带标签）→ 不同消费者订阅不同标签
                                    ├─ 普通订单消费者
                                    ├─ 秒杀订单消费者
                                    ├─ 预售订单消费者
                                    └─ VIP订单消费者
```

### 核心挑战
1. 如何根据订单类型路由到不同的消费者？
2. 如何实现复杂的过滤条件（如：地区、金额、用户等级）？
3. 如何保证过滤的性能？
4. 如何避免消息被错误消费？

---

## ❌ Buggy 版本的问题

### 问题 1：没有使用 Tag，所有消费者都收到所有消息

```java
// Bug: 所有消费者都订阅同一个 Topic，没有使用 Tag
// 导致每个消费者都收到所有类型的订单消息

// 生产者
Message message = provider.newMessageBuilder()
    .setTopic("order-topic")
    // Bug: 没有设置 Tag
    .setBody(orderJson.getBytes())
    .build();

// 消费者
FilterExpression filterExpression = new FilterExpression("*", FilterExpressionType.TAG);
// Bug: 订阅所有消息，无法区分订单类型
```

**问题现象：**
- 普通订单消费者收到秒杀订单，处理逻辑错误
- 秒杀订单消费者收到普通订单，浪费资源
- 消费者需要在代码中判断订单类型，增加复杂度

---

### 问题 2：Tag 设置错误，消息无法被消费

```java
// Bug: 生产者设置的 Tag 和消费者订阅的 Tag 不匹配

// 生产者
Message message = provider.newMessageBuilder()
    .setTopic("order-topic")
    .setTag("seckill-order")  // 设置为 "seckill-order"
    .build();

// 消费者
FilterExpression filterExpression = new FilterExpression(
    "seckill_order",  // Bug: 订阅 "seckill_order"（下划线）
    FilterExpressionType.TAG
);
```

**问题现象：**
- 消息发送成功，但消费者收不到
- 消息积压在 Broker
- 难以排查问题

---

### 问题 3：SQL 过滤表达式错误

```java
// Bug: SQL 表达式语法错误

// 生产者
Message message = provider.newMessageBuilder()
    .setTopic("order-topic")
    .addProperty("region", "beijing")
    .addProperty("amount", "100.00")
    .build();

// 消费者
FilterExpression filterExpression = new FilterExpression(
    "region = beijing AND amount > 100",  // Bug: beijing 应该加引号
    FilterExpressionType.SQL92
);
```

**问题现象：**
- 消费者启动失败
- 或者过滤条件不生效

---

### 问题 4：过滤性能问题

```java
// Bug: 使用复杂的 SQL 过滤，导致性能下降

FilterExpression filterExpression = new FilterExpression(
    "region IN ('beijing', 'shanghai', 'guangzhou', 'shenzhen') " +
    "AND amount > 100 " +
    "AND userLevel = 'VIP' " +
    "AND productCategory LIKE '%electronics%'",
    FilterExpressionType.SQL92
);
```

**问题现象：**
- 消费延迟增加
- Broker CPU 使用率升高
- 消息积压

---

## ✅ 解决方案设计

### 方案 1：使用 Tag 过滤（推荐，性能最好）

**适用场景：** 简单的分类过滤

```java
// 生产者：设置 Tag
Message message = provider.newMessageBuilder()
    .setTopic("order-topic")
    .setTag("seckill-order")  // 秒杀订单
    .setKeys(orderId)
    .setBody(orderJson.getBytes())
    .build();

// 消费者1：只订阅秒杀订单
FilterExpression filterExpression = new FilterExpression(
    "seckill-order",
    FilterExpressionType.TAG
);

// 消费者2：订阅多个 Tag
FilterExpression filterExpression = new FilterExpression(
    "seckill-order || presale-order",  // 秒杀订单或预售订单
    FilterExpressionType.TAG
);

// 消费者3：订阅所有
FilterExpression filterExpression = new FilterExpression(
    "*",  // 所有 Tag
    FilterExpressionType.TAG
);
```

**优点：**
- 性能最好（Broker 端过滤，基于 HashCode）
- 实现简单
- 支持多 Tag 订阅

**缺点：**
- 只能基于 Tag 过滤，不支持复杂条件

---

### 方案 2：使用 SQL92 过滤（复杂场景）

**适用场景：** 需要基于消息属性进行复杂过滤

```java
// 生产者：设置消息属性
Message message = provider.newMessageBuilder()
    .setTopic("order-topic")
    .setTag("normal-order")
    .setKeys(orderId)
    .addProperty("region", "beijing")      // 地区
    .addProperty("amount", "150.00")       // 金额
    .addProperty("userLevel", "VIP")       // 用户等级
    .setBody(orderJson.getBytes())
    .build();

// 消费者1：只消费北京地区的订单
FilterExpression filterExpression = new FilterExpression(
    "region = 'beijing'",
    FilterExpressionType.SQL92
);

// 消费者2：只消费金额大于 100 的订单
FilterExpression filterExpression = new FilterExpression(
    "amount > 100",
    FilterExpressionType.SQL92
);

// 消费者3：复合条件
FilterExpression filterExpression = new FilterExpression(
    "region = 'beijing' AND amount > 100 AND userLevel = 'VIP'",
    FilterExpressionType.SQL92
);
```

**支持的 SQL 语法：**
```sql
-- 数值比较
amount > 100
amount >= 100
amount < 100
amount <= 100
amount = 100
amount <> 100  -- 不等于
amount BETWEEN 100 AND 200

-- 字符串比较
region = 'beijing'
region <> 'beijing'
region IN ('beijing', 'shanghai', 'guangzhou')
region LIKE '%bei%'

-- 逻辑运算
region = 'beijing' AND amount > 100
region = 'beijing' OR region = 'shanghai'
NOT (region = 'beijing')

-- NULL 判断
region IS NULL
region IS NOT NULL
```

**优点：**
- 支持复杂的过滤条件
- 灵活性高

**缺点：**
- 性能低于 Tag 过滤
- 需要开启 Broker 的 SQL 过滤功能

---

### 方案 3：Tag + SQL 组合（推荐）

**适用场景：** 先用 Tag 粗过滤，再用 SQL 细过滤

```java
// 生产者
Message message = provider.newMessageBuilder()
    .setTopic("order-topic")
    .setTag("vip-order")  // 先用 Tag 分类
    .addProperty("region", "beijing")
    .addProperty("amount", "150.00")
    .setBody(orderJson.getBytes())
    .build();

// 消费者：Tag + SQL 组合
FilterExpression filterExpression = new FilterExpression(
    "(TAGS = 'vip-order') AND (region = 'beijing' AND amount > 100)",
    FilterExpressionType.SQL92
);
```

**优点：**
- 性能和灵活性的平衡
- Tag 在 Broker 端过滤，SQL 在消费者端过滤

---

### 方案 4：消费者端过滤（兜底方案）

**适用场景：** 过滤条件非常复杂，无法用 SQL 表达

```java
// 消费者：订阅所有消息，在代码中过滤
pushConsumer.setMessageListener(messageView -> {
    try {
        String body = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();
        Order order = objectMapper.readValue(body, Order.class);

        // 复杂的业务逻辑过滤
        if (!shouldProcess(order)) {
            log.info("订单不符合处理条件，跳过: {}", order.getOrderId());
            return ConsumeResult.SUCCESS;  // 返回成功，避免重试
        }

        // 处理订单
        processOrder(order);
        return ConsumeResult.SUCCESS;

    } catch (Exception e) {
        log.error("消息处理失败", e);
        return ConsumeResult.FAILURE;
    }
});

private boolean shouldProcess(Order order) {
    // 复杂的业务逻辑判断
    if (order.getAmount().compareTo(new BigDecimal("100")) <= 0) {
        return false;
    }
    if (!isVipUser(order.getUserId())) {
        return false;
    }
    if (!isValidRegion(order.getRegion())) {
        return false;
    }
    return true;
}
```

**优点：**
- 可以实现任意复杂的过滤逻辑
- 可以调用外部服务（如查询用户等级）

**缺点：**
- 性能最差（所有消息都要拉取到消费者）
- 浪费网络带宽
- 增加消费者负担

---

## 🧪 测试场景

### 场景 1：Tag 过滤 - 不同类型订单路由

```bash
# 1. 发送不同类型的订单
curl "http://localhost:8070/challenge/level8/sendOrder?type=normal&orderId=ORDER-001"
curl "http://localhost:8070/challenge/level8/sendOrder?type=seckill&orderId=ORDER-002"
curl "http://localhost:8070/challenge/level8/sendOrder?type=presale&orderId=ORDER-003"
curl "http://localhost:8070/challenge/level8/sendOrder?type=vip&orderId=ORDER-004"

# 2. 查看不同消费者的消费情况
curl "http://localhost:8070/challenge/level8/checkConsumerStats"

# 预期结果：
# - 普通订单消费者：只收到 ORDER-001
# - 秒杀订单消费者：只收到 ORDER-002
# - 预售订单消费者：只收到 ORDER-003
# - VIP订单消费者：只收到 ORDER-004
```

---

### 场景 2：SQL 过滤 - 基于地区和金额

```bash
# 1. 发送不同地区、不同金额的订单
curl "http://localhost:8070/challenge/level8/sendOrderWithProps?region=beijing&amount=150"
curl "http://localhost:8070/challenge/level8/sendOrderWithProps?region=shanghai&amount=80"
curl "http://localhost:8070/challenge/level8/sendOrderWithProps?region=beijing&amount=50"
curl "http://localhost:8070/challenge/level8/sendOrderWithProps?region=guangzhou&amount=200"

# 2. 查看北京地区消费者的消费情况
curl "http://localhost:8070/challenge/level8/checkConsumer?name=beijing-consumer"

# 预期结果：只收到北京地区的订单（ORDER-001, ORDER-003）

# 3. 查看高金额订单消费者的消费情况
curl "http://localhost:8070/challenge/level8/checkConsumer?name=high-amount-consumer"

# 预期结果：只收到金额 > 100 的订单（ORDER-001, ORDER-004）
```

---

### 场景 3：Buggy 版本 - Tag 不匹配

```bash
# 1. 发送秒杀订单（Tag: seckill-order）
curl "http://localhost:8070/challenge/level8/buggy/sendOrder?type=seckill&orderId=ORDER-001"

# 2. 查看消费者统计（消费者订阅 Tag: seckill_order）
curl "http://localhost:8070/challenge/level8/checkConsumerStats"

# Bug 现象：消息发送成功，但消费者收不到（Tag 不匹配）
```

---

### 场景 4：SQL 过滤性能测试

```bash
# 1. 批量发送 1000 条消息
curl "http://localhost:8070/challenge/level8/batchSend?count=1000"

# 2. 对比不同过滤方式的性能
curl "http://localhost:8070/challenge/level8/compareFilterPerformance"

# 预期结果：
# - Tag 过滤：最快
# - SQL 过滤：中等
# - 消费者端过滤：最慢
```

---

## 💡 核心知识点

### 1. Tag 过滤原理

```
Broker 端过滤流程：
1. 消息存储时，计算 Tag 的 HashCode
2. 消费者订阅时，指定 Tag
3. Broker 根据 HashCode 快速过滤
4. 只返回匹配的消息给消费者

时间复杂度：O(1)
```

### 2. SQL92 过滤原理

```
Broker 端过滤流程：
1. 消息存储时，保存消息属性
2. 消费者订阅时，指定 SQL 表达式
3. Broker 解析 SQL 表达式
4. 对每条消息执行 SQL 判断
5. 只返回匹配的消息给消费者

时间复杂度：O(n)
```

### 3. 过滤性能对比

| 过滤方式 | 过滤位置 | 性能 | 灵活性 | 网络开销 |
|---------|---------|------|--------|---------|
| Tag 过滤 | Broker | 高 | 低 | 低 |
| SQL 过滤 | Broker | 中 | 高 | 低 |
| 消费者过滤 | Consumer | 低 | 最高 | 高 |

### 4. 最佳实践

```java
// 1. 优先使用 Tag 过滤
// 适用场景：订单类型、消息类型等固定分类

// 2. Tag + SQL 组合
// 适用场景：先按类型分类，再按属性过滤

// 3. 避免过于复杂的 SQL
// 不推荐：region IN (...) AND amount > 100 AND userLevel = 'VIP' AND ...
// 推荐：先用 Tag 粗过滤，再用简单的 SQL

// 4. 消费者端过滤作为兜底
// 适用场景：需要调用外部服务判断的复杂逻辑
```

---

## 🎯 挑战目标

1. ✅ 理解 Tag 过滤和 SQL 过滤的原理
2. ✅ 实现基于 Tag 的消息路由
3. ✅ 实现基于 SQL 的复杂过滤
4. ✅ 对比不同过滤方式的性能
5. ✅ 避免常见的过滤错误
6. 🔧 设计合理的消息分类和过滤策略

---

## 📊 实战案例

### 案例 1：电商订单分类处理

```java
// 订单类型分类
Tag: normal-order, seckill-order, presale-order, vip-order

// 不同消费者订阅不同 Tag
- 普通订单消费者 → normal-order
- 秒杀订单消费者 → seckill-order
- 预售订单消费者 → presale-order
- VIP订单消费者 → vip-order
```

### 案例 2：日志分级处理

```java
// 日志级别分类
Tag: INFO, WARN, ERROR, FATAL

// 不同消费者订阅不同级别
- 日志存储消费者 → *（所有日志）
- 告警消费者 → ERROR || FATAL
- 监控消费者 → WARN || ERROR || FATAL
```

### 案例 3：地区路由

```java
// 使用 SQL 过滤
Property: region = beijing/shanghai/guangzhou/...

// 不同地区的消费者
- 北京消费者 → region = 'beijing'
- 上海消费者 → region = 'shanghai'
- 全国消费者 → region IN ('beijing', 'shanghai', 'guangzhou', ...)
```

---

## 🚀 扩展思考

1. 如何设计合理的 Tag 分类策略？
2. 什么场景下应该使用 SQL 过滤？
3. 如何监控过滤的效果和性能？
4. 如何处理过滤条件变更的情况？
5. 消息过滤 vs 多 Topic，如何选择？

---

## 📚 参考资料

- [RocketMQ 消息过滤官方文档](https://rocketmq.apache.org/docs/featureBehavior/07messagefilter)
- [SQL92 语法参考](https://rocketmq.apache.org/docs/featureBehavior/07messagefilter/#sql92-filter)

准备好挑战了吗？🚀
