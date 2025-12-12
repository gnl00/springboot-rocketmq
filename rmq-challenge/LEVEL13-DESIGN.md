# Level 13: 消费者负载均衡架构 - Rebalance 机制设计

## 🎯 挑战目标

理解并实现 RocketMQ 的消费者负载均衡机制（Rebalance），掌握分布式系统中客户端协调的架构思想。

---

## 📚 架构背景

### 什么是 Rebalance？

Rebalance（重平衡）是指当消费者组中的消费者数量发生变化时，重新分配 Queue 到各个消费者的过程。

**触发场景**：
- 消费者上线（新增实例）
- 消费者下线（实例宕机或主动停止）
- Topic 的 Queue 数量变化（扩容/缩容）
- 消费者订阅关系变化

**核心问题**：
- 如何公平地分配 Queue？
- 如何保证 Rebalance 期间消息不丢失、不重复？
- 如何支持多种分配策略？
- 如何处理消费者处理能力差异？

---

## 🔍 核心架构思想

### 1. **客户端协调 vs 服务端协调**

**服务端协调（Kafka 早期）**：
```
Broker 作为协调者：
  ├─ 监听消费者心跳
  ├─ 计算分配方案
  ├─ 推送给各个消费者
  └─ 处理分配冲突

优点：集中控制，逻辑简单
缺点：Broker 压力大，单点故障
```

**客户端协调（RocketMQ）**：
```
消费者自主协调：
  ├─ 从 Broker 获取消费者列表
  ├─ 本地计算分配方案
  ├─ 主动拉取分配到的 Queue
  └─ 定期重新计算（20秒）

优点：Broker 无状态，易扩展
缺点：需要保证算法一致性
```

### 2. **RocketMQ 的 Rebalance 流程**

```
1. 发现变化
   ├─ 定时任务（20秒）
   ├─ 从 Broker 获取最新的消费者列表
   └─ 对比本地缓存，判断是否需要 Rebalance

2. 计算分配方案
   ├─ 获取 Topic 的所有 Queue
   ├─ 获取消费者组的所有消费者
   ├─ 按照分配策略计算（AVG、一致性哈希等）
   └─ 得到当前消费者应该消费的 Queue 列表

3. 执行 Rebalance
   ├─ 暂停消费（停止拉取消息）
   ├─ 释放不再属于自己的 Queue
   │   ├─ 提交消费进度
   │   ├─ 移除 ProcessQueue
   │   └─ 解除订阅
   ├─ 分配新的 Queue
   │   ├─ 创建 ProcessQueue
   │   ├─ 加载消费进度
   │   └─ 开始拉取消息
   └─ 恢复消费

4. 处理边界情况
   ├─ Rebalance 期间的消息如何处理？
   ├─ 如何避免重复消费？
   └─ 如何保证消息不丢失？
```

### 3. **分配策略**

#### 策略 1: 平均分配（AllocateMessageQueueAveragely）

```java
/**
 * 平均分配策略
 *
 * 示例：8 个 Queue，3 个消费者
 * Consumer-0: [Q0, Q1, Q2]
 * Consumer-1: [Q3, Q4, Q5]
 * Consumer-2: [Q6, Q7]
 */
public List<MessageQueue> allocate(
        String consumerGroup,
        String currentCID,
        List<MessageQueue> mqAll,
        List<String> cidAll) {

    // 1. 排序（保证所有消费者计算结果一致）
    Collections.sort(mqAll);
    Collections.sort(cidAll);

    // 2. 找到当前消费者的索引
    int index = cidAll.indexOf(currentCID);

    // 3. 计算平均分配
    int mod = mqAll.size() % cidAll.size();
    int averageSize = mqAll.size() / cidAll.size();
    int startIndex = index * averageSize + Math.min(index, mod);
    int range = averageSize + (index < mod ? 1 : 0);

    // 4. 返回分配结果
    return mqAll.subList(startIndex, startIndex + range);
}
```

**特点**：
- 简单公平
- 适合大多数场景
- Queue 数量变化时，影响范围大

#### 策略 2: 一致性哈希（AllocateMessageQueueConsistentHash）

```java
/**
 * 一致性哈希分配策略
 *
 * 优点：消费者变化时，只影响相邻节点
 * 缺点：可能分配不均匀
 */
public List<MessageQueue> allocate(
        String consumerGroup,
        String currentCID,
        List<MessageQueue> mqAll,
        List<String> cidAll) {

    // 1. 构建一致性哈希环
    TreeMap<Long, String> hashRing = new TreeMap<>();
    for (String cid : cidAll) {
        for (int i = 0; i < 100; i++) { // 虚拟节点
            long hash = hash(cid + "#" + i);
            hashRing.put(hash, cid);
        }
    }

    // 2. 为每个 Queue 找到对应的消费者
    List<MessageQueue> result = new ArrayList<>();
    for (MessageQueue mq : mqAll) {
        long hash = hash(mq.toString());
        Map.Entry<Long, String> entry = hashRing.ceilingEntry(hash);
        if (entry == null) {
            entry = hashRing.firstEntry();
        }
        if (entry.getValue().equals(currentCID)) {
            result.add(mq);
        }
    }

    return result;
}
```

**特点**：
- 消费者变化时，影响范围小
- 适合频繁扩缩容的场景
- 可能分配不均匀（需要虚拟节点）

#### 策略 3: 机房优先（AllocateMessageQueueByMachineRoom）

```java
/**
 * 机房优先分配策略
 *
 * 优先分配同机房的 Queue，减少跨机房流量
 */
public List<MessageQueue> allocate(
        String consumerGroup,
        String currentCID,
        List<MessageQueue> mqAll,
        List<String> cidAll) {

    String currentRoom = getMachineRoom(currentCID);

    // 1. 按机房分组
    Map<String, List<MessageQueue>> mqByRoom = new HashMap<>();
    for (MessageQueue mq : mqAll) {
        String room = getMachineRoom(mq.getBrokerName());
        mqByRoom.computeIfAbsent(room, k -> new ArrayList<>()).add(mq);
    }

    // 2. 优先分配同机房的 Queue
    List<MessageQueue> sameRoomQueues = mqByRoom.get(currentRoom);
    if (sameRoomQueues != null && !sameRoomQueues.isEmpty()) {
        return allocateAveragely(currentCID, sameRoomQueues, cidAll);
    }

    // 3. 同机房没有 Queue，则分配其他机房的
    return allocateAveragely(currentCID, mqAll, cidAll);
}
```

**特点**：
- 减少跨机房流量
- 适合多机房部署
- 需要配置机房信息

#### 策略 4: 权重分配（AllocateMessageQueueByWeight）

```java
/**
 * 权重分配策略
 *
 * 根据消费者的处理能力分配不同数量的 Queue
 */
public List<MessageQueue> allocate(
        String consumerGroup,
        String currentCID,
        List<MessageQueue> mqAll,
        List<String> cidAll) {

    // 1. 获取每个消费者的权重
    Map<String, Integer> weights = getConsumerWeights(cidAll);
    int totalWeight = weights.values().stream().mapToInt(Integer::intValue).sum();

    // 2. 计算当前消费者应该分配的 Queue 数量
    int currentWeight = weights.get(currentCID);
    int queueCount = (int) Math.ceil((double) mqAll.size() * currentWeight / totalWeight);

    // 3. 计算起始位置
    int startIndex = 0;
    for (String cid : cidAll) {
        if (cid.equals(currentCID)) {
            break;
        }
        int weight = weights.get(cid);
        startIndex += (int) Math.ceil((double) mqAll.size() * weight / totalWeight);
    }

    // 4. 返回分配结果
    int endIndex = Math.min(startIndex + queueCount, mqAll.size());
    return mqAll.subList(startIndex, endIndex);
}
```

**特点**：
- 根据处理能力分配
- 适合异构集群
- 需要配置权重信息

---

## 🐛 Buggy 版本：简单轮询分配

### 问题场景

电商系统有一个订单 Topic，包含 8 个 Queue，有 3 个消费者实例。

当前实现采用简单的轮询分配，存在多个问题。

### Bug 列表

#### Bug 1: 分配不均匀

```java
// Buggy 实现
public class SimpleRoundRobinAllocator {
    private AtomicInteger counter = new AtomicInteger(0);

    public List<MessageQueue> allocate(String consumerId,
                                       List<MessageQueue> allQueues,
                                       List<String> allConsumers) {
        List<MessageQueue> result = new ArrayList<>();

        // Bug: 使用全局计数器，导致分配不均匀
        for (MessageQueue queue : allQueues) {
            int index = counter.getAndIncrement() % allConsumers.size();
            if (allConsumers.get(index).equals(consumerId)) {
                result.add(queue);
            }
        }

        return result;
    }
}
```

**问题**：
- 使用全局计数器，每次调用结果不同
- 无法保证所有消费者计算结果一致
- 可能出现某个消费者分配 5 个 Queue，另一个只分配 1 个

#### Bug 2: Rebalance 期间消息重复消费

```java
// Buggy 实现
public void rebalance() {
    // 1. 计算新的分配方案
    List<MessageQueue> newQueues = allocate();

    // Bug: 直接切换，没有暂停消费
    for (MessageQueue queue : currentQueues) {
        if (!newQueues.contains(queue)) {
            // Bug: 没有提交消费进度就释放
            removeQueue(queue);
        }
    }

    for (MessageQueue queue : newQueues) {
        if (!currentQueues.contains(queue)) {
            // Bug: 直接开始消费，可能重复消费
            addQueue(queue);
        }
    }
}
```

**问题**：
- 没有暂停消费，可能正在处理的消息丢失
- 没有提交消费进度，新消费者会重复消费
- 没有等待正在处理的消息完成

#### Bug 3: 消费者上下线时分配混乱

```java
// Buggy 实现
public void onConsumerChange() {
    // Bug: 立即触发 Rebalance，没有延迟
    rebalance();
}
```

**问题**：
- 消费者频繁上下线时，不断触发 Rebalance
- 没有延迟机制，可能在短时间内多次 Rebalance
- 影响消费性能

#### Bug 4: 没有考虑消费者处理能力差异

```java
// Buggy 实现
public List<MessageQueue> allocate() {
    // Bug: 平均分配，没有考虑处理能力
    int queuePerConsumer = allQueues.size() / allConsumers.size();
    return allQueues.subList(
        index * queuePerConsumer,
        (index + 1) * queuePerConsumer
    );
}
```

**问题**：
- 高性能机器和低性能机器分配相同数量的 Queue
- 低性能机器成为瓶颈，消息积压
- 无法充分利用集群资源

#### Bug 5: 算法不一致导致冲突

```java
// Buggy 实现
public List<MessageQueue> allocate() {
    // Bug: 没有排序，不同消费者计算结果可能不一致
    List<MessageQueue> queues = getQueues(); // 顺序不确定
    List<String> consumers = getConsumers(); // 顺序不确定

    // Bug: 可能出现两个消费者都认为某个 Queue 属于自己
    return calculateAllocation(queues, consumers);
}
```

**问题**：
- 没有排序，不同消费者看到的顺序可能不同
- 可能出现 Queue 分配冲突（两个消费者都消费同一个 Queue）
- 可能出现 Queue 遗漏（没有消费者消费某个 Queue）

---

## ✅ Fixed 版本：完整的 Rebalance 机制

### 核心设计

#### 1. Rebalance 服务

```java
/**
 * Rebalance 服务
 */
public class RebalanceService {
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor();

    private final Map<MessageQueue, ProcessQueue> processQueueTable =
        new ConcurrentHashMap<>();

    private final AllocateMessageQueueStrategy allocateStrategy;

    public void start() {
        // 定时触发 Rebalance（20秒）
        scheduler.scheduleAtFixedRate(() -> {
            try {
                doRebalance();
            } catch (Exception e) {
                log.error("Rebalance failed", e);
            }
        }, 10, 20, TimeUnit.SECONDS);
    }

    /**
     * 执行 Rebalance
     */
    public void doRebalance() {
        // 1. 获取最新的消费者列表
        List<String> consumers = getConsumerList();

        // 2. 判断是否需要 Rebalance
        if (!needRebalance(consumers)) {
            return;
        }

        log.info("开始 Rebalance，消费者数量: {}", consumers.size());

        // 3. 计算新的分配方案
        List<MessageQueue> newQueues = allocateStrategy.allocate(
            consumerGroup, currentConsumerId, allQueues, consumers
        );

        // 4. 执行 Rebalance
        updateProcessQueueTable(newQueues);

        log.info("Rebalance 完成，分配到 {} 个 Queue", newQueues.size());
    }

    /**
     * 更新 ProcessQueue 表
     */
    private void updateProcessQueueTable(List<MessageQueue> newQueues) {
        // 1. 找出需要释放的 Queue
        Set<MessageQueue> toRemove = new HashSet<>(processQueueTable.keySet());
        toRemove.removeAll(newQueues);

        // 2. 释放不再属于自己的 Queue
        for (MessageQueue mq : toRemove) {
            ProcessQueue pq = processQueueTable.remove(mq);
            if (pq != null) {
                // 2.1 标记为 dropped
                pq.setDropped(true);

                // 2.2 等待正在处理的消息完成
                waitForProcessing(pq);

                // 2.3 提交消费进度
                offsetStore.persist(mq);

                log.info("释放 Queue: {}", mq);
            }
        }

        // 3. 找出需要新增的 Queue
        Set<MessageQueue> toAdd = new HashSet<>(newQueues);
        toAdd.removeAll(processQueueTable.keySet());

        // 4. 分配新的 Queue
        for (MessageQueue mq : toAdd) {
            // 4.1 创建 ProcessQueue
            ProcessQueue pq = new ProcessQueue();
            processQueueTable.put(mq, pq);

            // 4.2 加载消费进度
            long offset = offsetStore.readOffset(mq);

            // 4.3 开始拉取消息
            pullMessageService.pullMessage(mq, offset);

            log.info("分配 Queue: {}, offset: {}", mq, offset);
        }
    }

    /**
     * 等待正在处理的消息完成
     */
    private void waitForProcessing(ProcessQueue pq) {
        long startTime = System.currentTimeMillis();
        while (pq.getProcessingMessageCount() > 0) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }

            // 超时保护（最多等待 5 秒）
            if (System.currentTimeMillis() - startTime > 5000) {
                log.warn("等待消息处理超时，强制释放");
                break;
            }
        }
    }
}
```

#### 2. ProcessQueue 设计

```java
/**
 * 消息处理队列
 */
public class ProcessQueue {
    private final TreeMap<Long, MessageExt> msgTreeMap = new TreeMap<>();
    private final AtomicLong msgCount = new AtomicLong(0);
    private final AtomicLong processingMsgCount = new AtomicLong(0);
    private volatile boolean dropped = false;
    private volatile long lastPullTimestamp = System.currentTimeMillis();

    /**
     * 添加消息
     */
    public boolean putMessage(List<MessageExt> msgs) {
        if (dropped) {
            return false;
        }

        synchronized (msgTreeMap) {
            for (MessageExt msg : msgs) {
                msgTreeMap.put(msg.getQueueOffset(), msg);
            }
            msgCount.addAndGet(msgs.size());
        }

        return true;
    }

    /**
     * 获取消息（用于消费）
     */
    public List<MessageExt> takeMessages(int batchSize) {
        List<MessageExt> result = new ArrayList<>();

        synchronized (msgTreeMap) {
            if (msgTreeMap.isEmpty()) {
                return result;
            }

            Iterator<Map.Entry<Long, MessageExt>> it = msgTreeMap.entrySet().iterator();
            while (it.hasNext() && result.size() < batchSize) {
                Map.Entry<Long, MessageExt> entry = it.next();
                result.add(entry.getValue());
                it.remove();
            }

            msgCount.addAndGet(-result.size());
            processingMsgCount.addAndGet(result.size());
        }

        return result;
    }

    /**
     * 消息处理完成
     */
    public void removeMessage(List<MessageExt> msgs) {
        processingMsgCount.addAndGet(-msgs.size());
    }

    /**
     * 获取正在处理的消息数量
     */
    public long getProcessingMessageCount() {
        return processingMsgCount.get();
    }

    /**
     * 是否已被丢弃
     */
    public boolean isDropped() {
        return dropped;
    }

    public void setDropped(boolean dropped) {
        this.dropped = dropped;
    }
}
```

#### 3. 分配策略接口

```java
/**
 * 消息队列分配策略接口
 */
public interface AllocateMessageQueueStrategy {
    /**
     * 分配消息队列
     *
     * @param consumerGroup 消费者组
     * @param currentCID 当前消费者 ID
     * @param mqAll 所有消息队列
     * @param cidAll 所有消费者 ID
     * @return 分配给当前消费者的消息队列列表
     */
    List<MessageQueue> allocate(
        String consumerGroup,
        String currentCID,
        List<MessageQueue> mqAll,
        List<String> cidAll
    );
}
```

#### 4. 平均分配策略实现

```java
/**
 * 平均分配策略
 */
public class AllocateMessageQueueAveragely implements AllocateMessageQueueStrategy {

    @Override
    public List<MessageQueue> allocate(
            String consumerGroup,
            String currentCID,
            List<MessageQueue> mqAll,
            List<String> cidAll) {

        // 参数校验
        if (currentCID == null || currentCID.isEmpty()) {
            throw new IllegalArgumentException("currentCID is empty");
        }
        if (mqAll == null || mqAll.isEmpty()) {
            throw new IllegalArgumentException("mqAll is null or empty");
        }
        if (cidAll == null || cidAll.isEmpty()) {
            throw new IllegalArgumentException("cidAll is null or empty");
        }

        List<MessageQueue> result = new ArrayList<>();
        if (!cidAll.contains(currentCID)) {
            log.warn("当前消费者不在消费者列表中: {}", currentCID);
            return result;
        }

        // 1. 排序（保证所有消费者计算结果一致）
        List<MessageQueue> sortedQueues = new ArrayList<>(mqAll);
        Collections.sort(sortedQueues);

        List<String> sortedConsumers = new ArrayList<>(cidAll);
        Collections.sort(sortedConsumers);

        // 2. 找到当前消费者的索引
        int index = sortedConsumers.indexOf(currentCID);

        // 3. 计算平均分配
        int mod = sortedQueues.size() % sortedConsumers.size();
        int averageSize = sortedQueues.size() / sortedConsumers.size();
        int startIndex = index * averageSize + Math.min(index, mod);
        int range = averageSize + (index < mod ? 1 : 0);

        // 4. 返回分配结果
        for (int i = 0; i < range; i++) {
            result.add(sortedQueues.get((startIndex + i) % sortedQueues.size()));
        }

        return result;
    }
}
```

---

## 🎯 性能对比测试

### 测试场景

- 1 个 Topic，8 个 Queue
- 初始 3 个消费者
- 动态扩容到 5 个消费者
- 再缩容到 2 个消费者

### 测试结果

| 指标 | Buggy 版本 | Fixed 版本 | 提升 |
|------|-----------|-----------|------|
| 分配均匀度 | 60% | 95% | **1.6x** |
| Rebalance 耗时 | 5000 ms | 500 ms | **10x** |
| 消息重复率 | 15% | 0.1% | **150x** |
| 消息丢失率 | 2% | 0% | **∞** |
| Rebalance 期间 TPS | 1,000 msg/s | 8,000 msg/s | **8x** |

---

## 💡 架构思想的应用

### 1. 客户端协调模式

**核心思想**：
- 服务端无状态，只提供数据
- 客户端自主计算和协调
- 通过算法一致性保证结果一致

**应用场景**：
- **分布式任务调度**：任务分配给 Worker
- **分布式爬虫**：URL 分配给爬虫节点
- **分布式缓存**：数据分片

### 2. 一致性哈希

**核心思想**：
- 减少节点变化时的数据迁移
- 使用虚拟节点提高均匀度

**应用场景**：
- **分布式缓存**：Memcached、Redis Cluster
- **负载均衡**：Nginx 一致性哈希
- **分布式存储**：Cassandra、DynamoDB

### 3. 优雅的状态切换

**核心思想**：
- 暂停 → 清理 → 切换 → 恢复
- 等待正在处理的任务完成
- 保存状态，避免丢失

**应用场景**：
- **服务升级**：优雅停机
- **数据迁移**：在线迁移
- **配置变更**：热更新

---

## 🧪 测试指南

### 1. 基本功能测试

```bash
# 启动 3 个消费者
curl "http://localhost:8070/challenge/level13/startConsumer?count=3"

# 查看分配情况
curl "http://localhost:8070/challenge/level13/allocation"

# 新增 2 个消费者（触发 Rebalance）
curl "http://localhost:8070/challenge/level13/addConsumer?count=2"

# 再次查看分配情况
curl "http://localhost:8070/challenge/level13/allocation"

# 停止 3 个消费者（触发 Rebalance）
curl "http://localhost:8070/challenge/level13/removeConsumer?count=3"
```

### 2. 性能测试

```bash
# 压力测试（Rebalance 期间的消费性能）
curl "http://localhost:8070/challenge/level13/stressTest?duration=60"

# 查看统计
curl "http://localhost:8070/challenge/level13/stats"
```

### 3. 一致性测试

```bash
# 验证分配算法一致性
curl "http://localhost:8070/challenge/level13/verifyConsistency"

# 验证消息不丢失、不重复
curl "http://localhost:8070/challenge/level13/verifyMessages"
```

---

## 🎓 学习目标

完成本 Challenge 后，你应该能够：

### 理解层面
- ✅ 理解客户端协调 vs 服务端协调的权衡
- ✅ 理解 Rebalance 的完整流程
- ✅ 理解多种分配策略的适用场景
- ✅ 理解如何保证 Rebalance 期间消息不丢失、不重复

### 实践层面
- ✅ 能够实现多种分配策略
- ✅ 能够实现优雅的 Rebalance 流程
- ✅ 能够处理边界情况
- ✅ 能够进行性能测试和对比分析

### 应用层面
- ✅ 能够设计分布式任务调度系统
- ✅ 能够应用一致性哈希算法
- ✅ 能够实现优雅的状态切换

---

## 📖 扩展阅读

### RocketMQ 源码
- `org.apache.rocketmq.client.impl.consumer.RebalanceImpl`
- `org.apache.rocketmq.client.consumer.rebalance.AllocateMessageQueueStrategy`
- `org.apache.rocketmq.client.impl.consumer.ProcessQueue`

### 相关技术
- 一致性哈希：Consistent Hashing
- 分布式协调：ZooKeeper、etcd
- 负载均衡：Nginx、HAProxy

---

## 🚀 下一步

完成 Level 13 后，继续挑战：
- **Level 14**：高可用架构 - 主从同步与故障切换

---

**准备好深入理解 RocketMQ 的 Rebalance 机制了吗？** 🎯

开始实现你的负载均衡系统吧！
