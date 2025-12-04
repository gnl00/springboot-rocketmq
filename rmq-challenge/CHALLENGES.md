# RocketMQ 生产问题挑战系统

欢迎来到 RocketMQ 生产问题挑战！这是一个实战训练系统，帮助你提升 RocketMQ 问题排查和解决能力。

## 🎯 挑战规则

1. 每个 Level 对应一个真实的生产环境问题
2. 代码中已经埋入了 Bug，你需要发现并修复
3. 每个问题都有详细的注释和提示
4. 建议按照 Level 顺序逐个完成

## 📚 挑战列表

### ✅ Level 1: 资源泄漏问题

**文件位置：** `sb-mq-producer/src/main/java/one/demo/challenge/Level1ProducerBuggy.java`

**问题现象：**
- 应用运行一段时间后内存持续增长
- 文件句柄数量不断增加
- 最终可能导致 OOM 或无法创建新线程

**测试方法：**
```bash
# 1. 启动生产者应用
cd sb-mq-producer
mvn spring-boot:run

# 2. 在另一个终端，快速发送多条消息
for i in {1..100}; do
  curl "http://localhost:8080/challenge/level1/send?message=测试消息-$i"
done

# 3. 观察内存使用情况
curl http://localhost:8080/challenge/level1/health

# 4. 或者使用批量发送接口
curl "http://localhost:8080/challenge/level1/batchSend?count=50"
```

**排查思路：**
1. 仔细阅读代码，找出可能导致资源泄漏的地方
2. 思考：每次 HTTP 请求都在创建什么？这些资源释放了吗？
3. 查看 RocketMQ Producer 的官方文档，了解其生命周期
4. 使用 JVM 监控工具（如 jconsole, VisualVM）观察线程和内存

**知识点：**
- RocketMQ Producer 的生命周期管理
- Java 资源管理和 try-with-resources
- 连接池的重要性

---

### ✅ Level 2: 消息发送失败与重试机制

**文件位置：** `sb-mq-producer/src/main/java/one/demo/challenge/Level2ProducerBuggy.java`

**问题现象：**
- 当 Broker 网络抖动时，API 接口超时（响应时间从 50ms 飙升到 30 秒）
- 应用日志显示大量重试消息，CPU 占用率飙升
- 部分消息发送失败后彻底丢失，无法追溯
- 并发情况下，大量请求被阻塞在等待重试

**测试方法：**
```bash
# 1. 启动生产者应用
cd sb-mq-producer
mvn spring-boot:run

# 2. 测试正常发送（观察响应时间）
time curl "http://localhost:8070/challenge/level2/send?message=测试消息"

# 3. 测试自定义重试逻辑
time curl "http://localhost:8070/challenge/level2/sendWithRetry?message=测试消息"

# 4. 【关键测试】模拟 Broker 故障（观察这个接口的响应时间！）
time curl "http://localhost:8070/challenge/level2/sendToInvalidBroker?message=故障测试"
# 你会发现这个接口会阻塞很长时间！

# 5. 批量发送测试（观察如果第一条消息失败，后续消息的影响）
time curl "http://localhost:8070/challenge/level2/batchSend?count=20"

# 6. 并发测试（模拟生产环境的并发场景）
for i in {1..20}; do
  curl "http://localhost:8070/challenge/level2/send?message=并发-$i" &
done
wait
```

**高级测试（模拟网络抖动）：**
```bash
# 你可以临时停止 RocketMQ Broker 来模拟网络故障
docker stop rmqbroker

# 然后发送消息，观察应用的表现
time curl "http://localhost:8070/challenge/level2/send?message=故障测试"

# 重新启动 Broker
docker start rmqbroker
```

**排查思路：**
1. **观察超时时间**：
   - 查看 `requestTimeout` 设置了多久？
   - 查看 `maxAttempts` 设置了多少次？
   - 计算最坏情况下的总耗时 = timeout × attempts

2. **分析重试逻辑**：
   - 重试之间有间隔吗？
   - 是否有指数退避策略？
   - 重试失败后有兜底方案吗？

3. **同步 vs 异步**：
   - 为什么同步发送会阻塞接口？
   - 异步发送有什么优势？
   - 如何在保证可靠性的同时提升性能？

4. **失败消息处理**：
   - 失败的消息去哪了？
   - 如何记录失败消息？
   - 如何实现失败消息的补偿机制？

**知识点：**
- RocketMQ 的同步发送、异步发送、单向发送
- 重试策略：固定间隔 vs 指数退避
- 超时时间与重试次数的权衡
- 失败消息的持久化与补偿机制
- 异步化改造提升系统吞吐量

**挑战目标：**
1. 找出代码中至少 5 个以上的问题
2. 设计合理的重试策略（考虑间隔、退避、最大次数）
3. 实现异步发送机制，避免阻塞主线程
4. 设计失败消息的兜底方案（如：持久化到数据库、写入文件等）
5. 确保在高并发场景下系统的稳定性

---

### ✅ Level 3: 消息重复消费与幂等性问题

**文件位置：** `sb-mq-producer/src/main/java/one/demo/challenge/level3/`

**问题现象：**
- 用户支付一笔订单，但账户余额被扣了多次
- 业务数据出现重复（重复发货、重复发券、重复积分）
- 数据库出现重复记录
- 用户投诉资金异常

**场景说明：**
订单支付成功后，系统发送 MQ 消息通知扣款服务进行扣款。由于网络抖动、消费者重启等原因，同一条消息可能被重复消费，导致用户被重复扣款。

**测试方法：**
```bash
# 1. 启动生产者应用（已包含消费者）
cd sb-mq-producer
mvn spring-boot:run

# 2. 查看初始账户余额
curl "http://localhost:8070/challenge/level3/getAllBalances"
# 返回：{"user001":1000.00,"user002":500.00,"user003":2000.00}

# 3. 用户 user001 支付一笔 100 元的订单
curl "http://localhost:8070/challenge/level3/payOrder?userId=user001&amount=100"
# 正常情况：余额应该从 1000 变成 900

# 4. 等待 2 秒让消息被消费
sleep 2

# 5. 查看余额（正常应该是 900）
curl "http://localhost:8070/challenge/level3/getBalance?userId=user001"

# 6. 【关键测试】模拟消息重复消费（同一个订单重复发送 3 次）
# 先获取刚才创建的订单ID（从步骤3的返回中获取，格式like ORDER-1234567890）
ORDER_ID="ORDER-1733211234567"  # 替换成实际的订单ID

curl "http://localhost:8070/challenge/level3/simulateDuplicateMessage?orderId=$ORDER_ID&times=3"

# 7. 等待消息被消费
sleep 2

# 8. 再次查看余额
curl "http://localhost:8070/challenge/level3/getBalance?userId=user001"
# Bug现象：余额可能变成 600（被扣了 4 次：1次正常 + 3次重复）
# 预期：余额应该还是 900（只扣一次）
```

**更简化的测试流程：**
```bash
# 1. 查看初始余额
curl "http://localhost:8070/challenge/level3/getAllBalances"

# 2. 支付 100 元
curl "http://localhost:8070/challenge/level3/payOrder?userId=user001&amount=100.00"

# 3. 从返回中复制 OrderId，然后模拟重复消费 5 次
curl "http://localhost:8070/challenge/level3/simulateDuplicateMessage?orderId=ORDER-xxx&times=5"

# 4. 查看余额，观察是否被重复扣款
curl "http://localhost:8070/challenge/level3/getBalance?userId=user001"
# 如果余额变成 400（1000 - 100*6），说明重复扣款了！
```

**排查思路：**
1. **理解 At Least Once 语义**：
   - RocketMQ 保证 At Least Once 投递
   - 什么情况会导致重复消费？（ACK 超时、消费者重启、网络闪断）

2. **分析业务代码**：
   - 扣款操作是否有幂等性保护？
   - 如何判断一条消息是否已经处理过？
   - 如果处理到一半失败了，重试会怎样？

3. **设计幂等方案**：
   - 方案1：数据库唯一索引（针对插入操作）
   - 方案2：分布式锁（Redis、Zookeeper）
   - 方案3：消息去重表（记录已处理的 MessageId）
   - 方案4：业务状态机（检查订单状态）
   - 如何选择合适的方案？

4. **异常处理**：
   - 哪些异常应该返回 SUCCESS？
   - 哪些异常应该返回 FAILURE 触发重试？
   - 如何避免无限重试？

**知识点：**
- RocketMQ 的投递语义：At Least Once vs Exactly Once
- 幂等性设计的常见方案
- 分布式系统中的幂等性保证
- 消息去重策略
- 异常重试的最佳实践

**挑战目标：**
1. 理解为什么会发生重复消费
2. 找出代码中至少 5 个问题
3. 设计并实现幂等性方案（至少实现 2 种不同的方案）
4. 确保即使消息重复消费，也不会重复扣款
5. 正确处理各种异常情况

---

### ✅ Level 4: 消息积压问题

**文件位置：** `sb-mq-producer/src/main/java/one/demo/challenge/level4/`

**问题现象：**
- 消息堆积数量持续增长（0 → 1000 → 10000 → ...）
- 消息消费延迟越来越高（秒级 → 分钟级 → 小时级）
- 消费者 CPU 使用率不高，但就是处理不过来
- 业务告警：订单、支付、通知等消息延迟严重

**场景说明：**
生产者每秒发送 100 条消息，但消费者处理每条消息需要 500ms，消费速度只有 2 msg/s，
远远跟不上生产速度，导致消息在 Broker 中堆积。

**核心问题：**
- 生产速度：100 msg/s
- 消费速度：2 msg/s (单线程 × 500ms/条)
- 积压速度：98 msg/s
- 1 分钟积压：98 × 60 = 5880 条

**测试方法：**
```bash
# 1. 启动应用
cd sb-mq-producer
mvn spring-boot:run

# 2. 快速发送 1000 条消息（模拟积压）
curl "http://localhost:8070/challenge/level4/produceMessages?count=1000&ratePerSecond=100"

# 3. 观察消费者日志中的消费速率
# Buggy 版本：约 2 msg/s
# 预期：大量消息积压

# 4. 持续发送消息（模拟生产压力）
curl "http://localhost:8070/challenge/level4/continuousProduce?ratePerSecond=100&durationSeconds=60"

# 5. 计算积压数量
# 发送：100 msg/s × 60s = 6000 条
# 消费：2 msg/s × 60s = 120 条
# 积压：6000 - 120 = 5880 条

# 6. 查看积压提示
curl "http://localhost:8070/challenge/level4/checkBacklog"
```

**排查思路：**
1. **分析消费速度瓶颈**：
   - 单线程消费效率如何？
   - 消费逻辑中有哪些耗时操作？
   - CPU 使用率是否饱和？

2. **计算理论消费能力**：
   - 单线程处理时间：500ms/条
   - 单线程消费速率：1000ms / 500ms = 2 msg/s
   - 需要多少线程才能跟上生产速度？100 / 2 = 50 线程

3. **优化策略选择**：
   - 方案1：增加消费者实例（水平扩容）
   - 方案2：增加单实例消费线程数
   - 方案3：优化消费逻辑（减少处理时间）
   - 方案4：异步化耗时操作
   - 方案5：批量处理优化

4. **业务逻辑优化**：
   - 数据库查询 → 缓存
   - 同步调用 → 异步调用
   - 单条处理 → 批量处理
   - 串行操作 → 并行操作

**优化方案对比：**

| 优化方案 | 效果 | 成本 | 风险 | 推荐度 |
|---------|------|------|------|--------|
| 增加消费者实例 | 线性提升 | 高（资源） | 低 | ⭐⭐⭐⭐ |
| 增加消费线程数 | 显著提升 | 低 | 中（线程竞争） | ⭐⭐⭐⭐⭐ |
| 优化消费逻辑 | 大幅提升 | 低 | 低 | ⭐⭐⭐⭐⭐ |
| 异步化处理 | 大幅提升 | 低 | 高（可靠性） | ⭐⭐⭐ |
| 批量处理 | 中等提升 | 低 | 中（复杂度） | ⭐⭐⭐⭐ |

**知识点：**
- 消息积压的常见原因和排查方法
- 消费者性能优化策略
- 消费并发度配置
- 异步化改造的利弊
- 批量处理的实现方式
- 消费者扩容策略

**挑战目标：**
1. 分析 Buggy 版本的性能瓶颈
2. 计算需要多少消费能力才能不积压
3. 实现至少 2 种优化方案
4. 将消费速率提升到 100 msg/s 以上
5. 确保优化后的可靠性

**性能指标：**
- Buggy 版本：2 msg/s
- 优化目标：≥ 100 msg/s
- 优秀水平：≥ 200 msg/s

---

### ✅ Level 5: 顺序消息混乱问题

**文件位置：** `sb-mq-producer/src/main/java/one/demo/challenge/level5/`

**问题现象：**
- 订单还未支付就显示已发货
- 订单还未创建就收到支付消息
- 状态转换混乱，业务逻辑错误
- 数据库中订单状态不一致

**场景说明：**
订单状态必须按照特定顺序流转：**创建 → 支付 → 发货 → 完成**

但是由于消息发送和消费的无序性，导致状态更新混乱：
- 消息 3（发货）在消息 2（支付）之前被处理
- 消息 4（完成）在消息 3（发货）之前到达
- 多个订单的消息交织在一起，处理顺序完全混乱

**核心问题：**
1. **生产者**：使用普通消息发送，RocketMQ 随机选择队列
2. **消费者**：并发消费模式，多线程同时处理
3. **结果**：同一订单的消息进入不同队列，被不同线程并发处理，顺序无法保证

**测试方法：**
```bash
# 1. 启动应用
cd sb-mq-producer
mvn spring-boot:run

# 2. 查看测试指南
curl "http://localhost:8070/challenge/level5/help"

# 3. 发送单个订单状态流转
curl "http://localhost:8070/challenge/level5/simulateOrderFlow?orderId=ORDER-001"

# 4. 查看订单状态（观察是否有错误）
curl "http://localhost:8070/challenge/level5/checkOrderStatus?orderId=ORDER-001"
# 预期问题：错误数 > 0，说明出现乱序

# 5. 并发测试（加剧乱序现象）
curl "http://localhost:8070/challenge/level5/simulateMultipleOrders?count=5"

# 6. 重置测试环境
curl "http://localhost:8070/challenge/level5/reset"
```

**问题示例：**
```
预期顺序：创建(seq=1) → 支付(seq=2) → 发货(seq=3) → 完成(seq=4)

实际日志：
✅ 订单 ORDER-001 状态更新成功: 订单创建 (seq=1)
📥 收到 seq=3 (发货) - Thread-2
❌ 订单 ORDER-001 状态转换非法！订单创建 -> 已发货
📥 收到 seq=2 (支付) - Thread-3
⚠️ 订单 ORDER-001 收到乱序消息！当前序列号: 3, 收到序列号: 2
```

**排查思路：**
1. **理解 RocketMQ 的顺序保证**：
   - 普通消息：无序，随机分配到不同队列
   - 同一队列内的消息：FIFO 有序
   - 并发消费：多线程处理，无序

2. **分析乱序原因**：
   - 为什么同一订单的消息会进入不同队列？
   - 为什么并发消费会导致乱序？
   - 如何保证同一订单的消息顺序处理？

3. **顺序消息解决方案**：
   - **生产者**：使用 `MessageQueueSelector` 指定队列
     ```java
     // 伪代码示例
     producer.send(message, messageQueueSelector, orderId);
     // 同一 orderId 总是选择同一队列
     ```
   - **消费者**：使用顺序消费模式（Orderly）
     ```java
     consumeMode = ConsumeMode.ORDERLY
     // 同一队列的消息被同一线程顺序处理
     ```

4. **权衡与注意事项**：
   - 顺序消息吞吐量较低（单线程处理）
   - 如果一条消息阻塞，后续消息都会延迟
   - 队列数量决定了最大并发度
   - 需要合理设计分区键（orderId、userId 等）

**知识点：**
- RocketMQ 的消息顺序保证机制
- 普通消息 vs 顺序消息
- MessageQueueSelector 的使用
- 并发消费 vs 顺序消费
- 分区策略与分区键选择
- 顺序消息的性能权衡

**挑战目标：**
1. 运行 Buggy 版本，观察乱序现象
2. 理解为什么会出现乱序
3. 创建 `Level5ProducerFixed.java` 和 `Level5ConsumerFixed.java`
4. 实现顺序消息发送和消费
5. 验证修复效果：错误数 = 0，状态正确流转

**验收标准：**
- Buggy 版本：错误数 > 0，日志中有乱序警告
- Fixed 版本：错误数 = 0，所有订单状态正确
- 并发测试 10 个订单，全部成功

---

### ✅ Level 6: 事务消息问题

**文件位置：** `rmq-challenge/src/main/java/one/demo/challenge/level6/`

**问题现象：**
- 订单创建成功，但消息发送失败 → 库存未扣减，积分未增加
- 消息发送成功，但订单创建失败 → 库存被扣减，积分被增加，但订单不存在
- 订单创建过程中异常，但消息已发送 → 数据不一致
- 网络抖动导致消息重复发送 → 库存重复扣减，积分重复增加

**场景说明：**
用户下单后，需要完成三个操作：
1. **创建订单**（本地数据库）
2. **扣减库存**（下游服务，通过MQ通知）
3. **增加积分**（下游服务，通过MQ通知）

这三个操作必须保持一致性：**要么全部成功，要么全部失败**。

**核心问题：**
本地事务和消息发送不是原子操作，无法保证一致性：
- 先创建订单，再发送消息 → 消息发送失败时，订单已创建
- 先发送消息，再创建订单 → 订单创建失败时，消息已发送
- 使用try-catch回滚 → 回滚操作本身可能失败，且无法保证原子性

**测试方法：**
```bash
# 1. 启动应用
cd rmq-challenge
mvn spring-boot:run

# 2. 查看帮助信息
curl "http://localhost:8070/challenge/level6/help"

# 3. 重置数据
curl "http://localhost:8070/challenge/level6/reset"

# 4. 查看初始状态
curl "http://localhost:8070/challenge/level6/checkAll"
# 返回：订单=0, 库存=PRODUCT-001:100, 积分=USER-001:0

# 5. 【Buggy版本】模拟消息发送失败
curl "http://localhost:8070/challenge/level6/buggy/simulateMessageFailure?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

# 6. 检查数据一致性
curl "http://localhost:8070/challenge/level6/checkAll"
# Bug现象：订单已创建，但库存和积分未变化（数据不一致）

# 7. 重置数据
curl "http://localhost:8070/challenge/level6/reset"

# 8. 【Buggy版本】模拟订单创建失败
curl "http://localhost:8070/challenge/level6/buggy/simulateOrderFailure?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

# 9. 检查数据一致性
curl "http://localhost:8070/challenge/level6/checkAll"
# Bug现象：订单不存在，但库存和积分已变化（数据不一致）

# 10. 重置数据
curl "http://localhost:8070/challenge/level6/reset"

# 11. 【Fixed版本】使用事务消息创建订单
curl "http://localhost:8070/challenge/level6/fixed/createOrder?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

# 12. 检查数据一致性
curl "http://localhost:8070/challenge/level6/checkAll"
# 预期：订单已创建，库存已扣减，积分已增加（数据一致）

# 13. 重置数据
curl "http://localhost:8070/challenge/level6/reset"

# 14. 【Fixed版本】模拟本地事务失败
curl "http://localhost:8070/challenge/level6/fixed/simulateLocalTransactionFailure?userId=USER-001&productId=PRODUCT-001&quantity=5&amount=100.00"

# 15. 检查数据一致性
curl "http://localhost:8070/challenge/level6/checkAll"
# 预期：订单不存在，库存和积分未变化（数据一致）
```

**排查思路：**
1. **理解问题本质**：
   - 为什么先创建订单再发送消息会有问题？
   - 为什么先发送消息再创建订单会有问题？
   - 为什么try-catch回滚无法保证原子性？

2. **分析Buggy版本的三种方案**：
   - 方案1：先创建订单，再发送消息
   - 方案2：先发送消息，再创建订单
   - 方案3：使用try-catch包裹，失败时回滚
   - 思考：为什么这三种方案都无法保证一致性？

3. **学习RocketMQ事务消息**：
   - 什么是Half消息？
   - 事务消息的工作流程是什么？
   - 什么是事务回查？为什么需要回查？
   - 如何实现TransactionChecker？

4. **事务消息工作流程**：
   ```
   1. 发送 Half 消息（对消费者不可见）
   2. 执行本地事务（创建订单）
   3. 根据本地事务结果：
      - 成功 → Commit（消息对消费者可见）
      - 失败 → Rollback（消息被删除）
   4. 如果长时间未收到确认，Broker 回查事务状态
   ```

5. **实现要点**：
   - 使用 `producer.beginTransaction()` 开始事务
   - 在事务中发送消息
   - 执行本地事务
   - 根据结果调用 `transaction.commit()` 或 `transaction.rollback()`
   - 实现 `TransactionChecker` 用于回查

**知识点：**
- 分布式事务的一致性问题
- RocketMQ 事务消息的原理和实现
- Half 消息机制
- 事务回查机制
- 本地事务与消息发送的最终一致性
- 事务消息 vs 2PC vs TCC vs Saga

**挑战目标：**
1. 运行 Buggy 版本，观察数据不一致现象
2. 理解为什么普通消息无法保证一致性
3. 学习 RocketMQ 事务消息的工作原理
4. 分析 Fixed 版本的实现
5. 理解事务回查的作用和实现方式
6. 思考：如何在生产环境中持久化事务状态？

**性能对比：**
- Buggy 版本：数据不一致，无法用于生产环境
- Fixed 版本：保证最终一致性，但性能略低于普通消息

**扩展思考：**
1. 事务消息适用于哪些场景？
2. 事务消息有哪些限制？
3. 如果下游服务处理失败，如何回滚？
4. 如何在生产环境中实现事务状态的持久化？
5. 事务消息 vs 本地消息表 vs Saga 模式，如何选择？

---

## 🚀 进阶挑战 (Level 7-12)

完成 Level 1-6 后，你已经掌握了 RocketMQ 的核心特性。接下来的挑战将带你进入更高级的领域！

### 🔒 Level 7: 延时消息与定时任务（设计中）

**难度：** ⭐⭐⭐⭐

**核心问题：** 订单超时未支付自动取消

**场景：** 用户下单后 30 分钟未支付，自动取消订单并恢复库存。

**技术点：**
- RocketMQ 延时消息（18个延时等级）
- 时间轮算法
- 延时任务的取消机制
- 定时任务的幂等性

**挑战：**
- RocketMQ 只支持固定的 18 个延时等级，如何实现任意时间延时？
- 用户支付后，如何取消已发送的延时消息？
- 如何保证延时任务在分布式环境下只执行一次？

**详细设计：** 查看 `LEVEL7-DESIGN.md`

---

### 🔒 Level 8: 消息过滤与标签路由（设计中）

**难度：** ⭐⭐⭐

**核心问题：** 不同类型的订单需要不同的处理逻辑

**场景：** 普通订单、秒杀订单、预售订单、VIP订单需要路由到不同的消费者。

**技术点：**
- Tag 过滤
- SQL92 过滤
- 消息属性（Properties）
- 消费者订阅表达式

**挑战：**
- 如何设计合理的 Tag 分类策略？
- 什么场景下应该使用 SQL 过滤？
- 如何平衡过滤的灵活性和性能？

**详细设计：** 查看 `LEVEL8-DESIGN.md`

---

### 🔒 Level 9: 死信队列与消息重试（设计中）

**难度：** ⭐⭐⭐⭐

**核心问题：** 消息消费失败后的处理

**场景：** 消息消费失败，重试 3 次后进入死信队列，需要监控和人工介入。

**技术点：**
- 死信队列（DLQ）
- 消息重试机制
- 重试次数配置
- 死信消息的重新投递

**挑战：**
- 如何区分业务异常和系统异常？
- 如何设置合理的重试次数和间隔？
- 死信队列如何监控和处理？
- 如何重新投递死信消息？

**详细设计：** 查看 `LEVEL9-12-DESIGN.md`

---

### 🔒 Level 10: 消息轨迹与链路追踪（设计中）

**难度：** ⭐⭐⭐⭐

**核心问题：** 消息在分布式系统中的完整链路追踪

**场景：** 订单系统 → MQ → 库存系统 → MQ → 物流系统，需要追踪消息的完整链路。

**技术点：**
- 消息轨迹（Message Trace）
- TraceId 传递
- 分布式链路追踪（OpenTelemetry）
- 消息监控与告警

**挑战：**
- 如何在分布式系统中传递 TraceId？
- 如何记录消息的完整链路？
- 如何定位消息丢失的环节？
- 如何控制链路追踪的性能开销？

**详细设计：** 查看 `LEVEL9-12-DESIGN.md`

---

### 🔒 Level 11: 消息优先级与流控（设计中）

**难度：** ⭐⭐⭐⭐⭐

**核心问题：** 高优先级消息被低优先级消息阻塞

**场景：** VIP 订单需要优先处理，但被大量普通订单阻塞。

**技术点：**
- 消息优先级
- 多队列优先级调度
- 消费者流控
- 令牌桶算法

**挑战：**
- RocketMQ 不直接支持消息优先级，如何实现？
- 如何防止低优先级消息饿死？
- 如何限制消费速率，保护下游系统？
- 如何在保证优先级的同时保持高吞吐量？

**详细设计：** 查看 `LEVEL9-12-DESIGN.md`

---

### 🔒 Level 12: 多机房容灾与消息同步（设计中）

**难度：** ⭐⭐⭐⭐⭐⭐

**核心问题：** 跨机房的消息同步与容灾切换

**场景：** 主机房故障，自动切换到备机房，保证消息不丢失、不重复。

**技术点：**
- 多机房部署
- 消息同步（Dledger）
- 主备切换
- 消息去重与幂等
- 脑裂问题

**挑战：**
- 如何实现主备机房的消息同步？
- 主机房故障时，如何快速切换？
- 切换过程中如何保证消息不丢失？
- 如何防止脑裂问题？

**详细设计：** 查看 `LEVEL9-12-DESIGN.md`

---

## 📊 挑战难度梯度

```
基础篇 (Level 1-3)  ⭐-⭐⭐⭐
├── Level 1: 资源泄漏问题
├── Level 2: 消息发送失败与重试
└── Level 3: 消息重复消费与幂等性

进阶篇 (Level 4-6)  ⭐⭐⭐-⭐⭐⭐⭐⭐
├── Level 4: 消息积压问题
├── Level 5: 顺序消息混乱
└── Level 6: 事务消息问题

高级篇 (Level 7-9)  ⭐⭐⭐⭐
├── Level 7: 延时消息与定时任务
├── Level 8: 消息过滤与标签路由
└── Level 9: 死信队列与消息重试

架构篇 (Level 10-12) ⭐⭐⭐⭐⭐-⭐⭐⭐⭐⭐⭐
├── Level 10: 消息轨迹与链路追踪
├── Level 11: 消息优先级与流控
└── Level 12: 多机房容灾与消息同步
```

## 🎯 学习路径建议

### 初级开发者（1-2周）
完成 Level 1-3，掌握 RocketMQ 基本使用和常见问题处理。

### 中级开发者（2-3周）
完成 Level 4-6，掌握性能优化、顺序消息和事务消息。

### 高级开发者（3-4周）
完成 Level 7-9，掌握延时消息、过滤、死信队列等高级特性。

### 架构师（4-6周）
完成 Level 10-12，掌握链路追踪、流控、多机房容灾等架构级能力。

---

## 📖 如何提交答案

当你认为已经修复了问题，可以：

1. 创建修复后的代码文件（命名为 `Level1ProducerFixed.java`）
2. 编写测试验证修复效果
3. 记录你的分析过程和解决方案
4. 与我讨论你的方案是否正确

## 🎓 学习建议

1. **不要直接看答案**：先自己分析和尝试
2. **使用监控工具**：学会使用 JVM 监控工具观察问题
3. **查阅官方文档**：养成查阅官方文档的习惯
4. **记录笔记**：记录每个问题的分析过程和知识点

## 🆘 需要帮助？

如果遇到困难，可以：
1. 查看代码中的注释和提示
2. 搜索相关的错误信息
3. 查阅 RocketMQ 官方文档
4. 向我寻求提示（但不要直接要答案）

---

**开始你的第一个挑战吧！祝你好运！** 🚀
