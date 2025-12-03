# Level 3 快速测试指南

## 🎯 测试目标
验证三个版本的幂等性处理效果：
- **Buggy 版本**：无幂等性保护，必定重复扣款
- **Fixed 版本**：你的修复版本，可能有并发问题
- **Best 版本**：最佳实践，完全幂等

---

## 🚀 快速开始

### 1. 启动应用
```bash
cd sb-mq-producer
mvn spring-boot:run
```

---

## 📋 测试方案对比

### 方案 A：测试 Buggy 版本（演示问题）

```bash
# 1. 查看初始余额
curl "http://localhost:8070/challenge/level3/getAllBalances"
# 返回: {"user001":1000.00,"user002":500.00,"user003":2000.00}

# 2. 支付 100 元订单
curl "http://localhost:8070/challenge/level3/payOrder?userId=user001&amount=100"
# 记录返回的 OrderId，例如：ORDER-1733212345678

# 3. 等待消费
sleep 2

# 4. 查看余额（应该是 900）
curl "http://localhost:8070/challenge/level3/getBalance?userId=user001"

# 5. 模拟重复消费 5 次
ORDER_ID="ORDER-1733212345678"  # 替换成步骤2的实际 OrderId
curl "http://localhost:8070/challenge/level3/simulateDuplicateMessage?orderId=$ORDER_ID&times=5"

# 6. 等待消费
sleep 2

# 7. 再次查看余额
curl "http://localhost:8070/challenge/level3/getBalance?userId=user001"
# ❌ Bug现象：余额可能变成 400（被扣了6次：1000 - 100*6）
```

---

### 方案 B：测试 Fixed 版本（你的版本）

**注意：** 你的 Fixed 版本使用的 tag 是 `payment-fixed`，但原始的 Level3Producer 发送的 tag 是 `payment`。

**你需要先修改 tag 才能测试：**

```bash
# 临时测试方案：修改 Level3Producer.java 中的 tag
# 将 .setTag("payment") 改为 .setTag("payment-fixed")

# 或者直接测试 Best 版本
```

---

### 方案 C：测试 Best 版本（推荐）

#### 方法 1：手动测试
```bash
# 1. 查看初始余额
curl "http://localhost:8070/challenge/level3/best/getAllBalances"

# 2. 支付 100 元订单
curl "http://localhost:8070/challenge/level3/best/payOrder?userId=user001&amount=100"
# 返回示例：✅ 支付订单成功 - OrderId: ORDER-1733212345678, MessageId: xxx

# 3. 等待 2 秒
sleep 2

# 4. 查看余额（应该是 900）
curl "http://localhost:8070/challenge/level3/best/getBalance?userId=user001"

# 5. 模拟重复消费 5 次
ORDER_ID="ORDER-1733212345678"  # 替换成步骤2的实际 OrderId
curl "http://localhost:8070/challenge/level3/best/simulateDuplicate?orderId=$ORDER_ID&times=5"

# 6. 等待 2 秒
sleep 2

# 7. 再次查看余额
curl "http://localhost:8070/challenge/level3/best/getBalance?userId=user001"
# ✅ 预期：余额还是 900（只扣了1次，幂等性保护生效）

# 8. 查看去重统计
curl "http://localhost:8070/challenge/level3/best/deduplicationStats"
# 返回：📊 去重统计 - 已处理消息数: 6（1次正常 + 5次重复 = 6条消息）
```

#### 方法 2：自动化完整测试（最推荐）
```bash
# 一键运行完整测试流程
curl "http://localhost:8070/challenge/level3/best/fullTest"

# 输出示例：
# 1️⃣ 初始余额: user001 = 1000.00 元
# 2️⃣ 已发送支付消息 - OrderId: ORDER-xxx
# 3️⃣ 第一次扣款后余额: 900.00 元
# 4️⃣ 发送 5 条重复消息...
# 5️⃣ 重复消息处理后余额: 900.00 元
#
# ✅ 测试通过！余额正确，幂等性保护生效！
#    预期余额: 900.00 元，实际余额: 900.00 元
```

---

## 🔍 并发测试（高级）

测试 Fixed 版本在高并发下的竞态条件：

```bash
# 1. 创建订单
curl "http://localhost:8070/challenge/level3/best/payOrder?userId=user003&amount=50"
ORDER_ID="获取订单ID"

# 2. 并发发送 10 个相同的消息（模拟并发重复消费）
for i in {1..10}; do
  curl "http://localhost:8070/challenge/level3/simulateDuplicateMessage?orderId=$ORDER_ID&times=1" &
done
wait

# 3. 等待消费
sleep 3

# 4. 查看余额
curl "http://localhost:8070/challenge/level3/getBalance?userId=user003"
# Fixed版本：可能被扣了多次（并发问题）
# Best版本：只扣了1次（正确）
```

---

## 📊 测试结果对比

### 预期结果

| 测试场景 | Buggy 版本 | Fixed 版本 | Best 版本 |
|---------|-----------|-----------|----------|
| 单次消费 | ✅ 正常 | ✅ 正常 | ✅ 正常 |
| 顺序重复消费 | ❌ 重复扣款 | ⚠️ 可能正常 | ✅ 正常 |
| 并发重复消费 | ❌ 重复扣款 | ❌ 重复扣款 | ✅ 正常 |
| 应用重启后 | ❌ 重复扣款 | ❌ 重复扣款 | ⚠️ 需持久化 |

### 示例输出

**Buggy 版本（重复扣款）：**
```
初始余额：1000.00
第1次消费：1000 - 100 = 900
第2次消费：900 - 100 = 800  ❌
第3次消费：800 - 100 = 700  ❌
...
最终余额：400.00  ❌ 错误！
```

**Best 版本（幂等保护）：**
```
初始余额：1000.00
第1次消费：1000 - 100 = 900  ✅
第2次消费：检测到重复，跳过  ✅
第3次消费：检测到重复，跳过  ✅
...
最终余额：900.00  ✅ 正确！
```

---

## 🐛 如何观察问题

### 观察日志

启动应用后，观察日志中的关键信息：

**Buggy 版本的日志：**
```
收到支付消息 - MessageId: xxx, OrderId: ORDER-001
【重要】扣款成功 - UserId: user001, Amount: 100, 剩余余额: 900
收到支付消息 - MessageId: yyy, OrderId: ORDER-001  ← 重复消息
【重要】扣款成功 - UserId: user001, Amount: 100, 剩余余额: 800  ← 又扣了一次！
```

**Best 版本的日志：**
```
收到支付消息 - MessageId: xxx, OrderId: ORDER-001
首次处理消息 - MessageId: xxx
【扣款成功】UserId: user001, Amount: 100, 剩余余额: 900
收到支付消息 - MessageId: yyy, OrderId: ORDER-001  ← 重复消息
检测到重复消息 - MessageId: yyy, 首次处理时间: 2025-12-03T15:30:00  ← 被拦截
重复消息已跳过 - MessageId: yyy, OrderId: ORDER-001
```

---

## 💡 调试技巧

### 1. 增加日志级别
```yaml
# application.yml
logging:
  level:
    one.demo.challenge.level3: DEBUG
```

### 2. 查看实时余额变化
```bash
# 循环监控余额
while true; do
  curl -s "http://localhost:8070/challenge/level3/best/getBalance?userId=user001"
  sleep 1
done
```

### 3. 使用 JMeter 压测
创建 JMeter 测试计划：
- 100 个并发线程
- 每个线程发送相同的订单ID
- 观察最终余额是否正确

---

## ✅ 验收标准

**Fixed 版本通过标准：**
- ✅ 单次消费：正确扣款
- ✅ 顺序重复消费：不重复扣款
- ⚠️ 并发重复消费：可能失败（并发问题）

**Best 版本通过标准：**
- ✅ 单次消费：正确扣款
- ✅ 顺序重复消费：不重复扣款
- ✅ 并发重复消费：不重复扣款
- ✅ 压力测试：1000次并发，余额正确

---

## 🎓 学习总结

完成测试后，你应该理解：

1. **什么是幂等性？**
   - 多次执行相同操作，结果与执行一次相同

2. **为什么需要幂等性？**
   - RocketMQ 保证 At Least Once
   - 网络抖动、消费者重启都可能导致重复消费

3. **如何实现幂等性？**
   - 消息去重表
   - 业务状态机
   - 业务层幂等操作
   - 数据库唯一索引

4. **并发问题怎么解决？**
   - 使用原子操作（putIfAbsent）
   - 避免 Check-Then-Act 模式
   - 使用分布式锁

---

**开始你的测试吧！** 🚀

如果有问题，可以查看 `LEVEL3-ANALYSIS.md` 获取详细分析。