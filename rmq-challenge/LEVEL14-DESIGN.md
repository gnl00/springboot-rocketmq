# Level 14: 高可用架构 - 主从同步与故障切换

## 🎯 挑战目标

理解并实现 RocketMQ 的高可用架构，掌握主从同步、故障检测、自动切换等分布式系统核心能力。

---

## 📚 架构背景

### 为什么需要高可用？

在生产环境中，单点故障是不可接受的：
- **Broker 宕机**：消息无法发送和消费
- **磁盘故障**：消息永久丢失
- **网络分区**：部分节点不可用
- **机房故障**：整个机房不可用

**高可用的目标**：
- 数据不丢失（Durability）
- 服务不中断（Availability）
- 自动故障恢复（Fault Tolerance）

---

## 🔍 核心架构思想

### 1. **RocketMQ 的高可用演进**

#### 阶段 1: 主从异步复制（Master-Slave）

```
Master (可读可写)
  ├─ 接收消息写入
  ├─ 异步复制到 Slave
  └─ 提供消息消费

Slave (只读)
  ├─ 从 Master 同步数据
  ├─ 提供消息消费（分担读压力）
  └─ Master 宕机后，手动切换
```

**优点**：
- 实现简单
- 性能高（异步复制）
- 读写分离，分担压力

**缺点**：
- Master 宕机可能丢消息
- 需要手动切换
- 无法保证强一致性

#### 阶段 2: 主从同步复制（Sync Master-Slave）

```
Master (可读可写)
  ├─ 接收消息写入
  ├─ 同步复制到 Slave（等待 ACK）
  ├─ Slave ACK 后才返回成功
  └─ 保证数据不丢失

Slave (只读)
  ├─ 同步接收数据
  ├─ 立即返回 ACK
  └─ Master 宕机后，手动切换
```

**优点**：
- 数据不丢失（强一致性）
- 可靠性高

**缺点**：
- 性能下降（同步等待）
- 仍需手动切换
- Slave 宕机影响写入

#### 阶段 3: Dledger 模式（自动切换）

```
基于 Raft 协议的多副本架构：
  ├─ Leader（可读可写）
  ├─ Follower 1（只读）
  ├─ Follower 2（只读）
  └─ 自动选举和切换

特点：
  ├─ 自动故障检测
  ├─ 自动 Leader 选举
  ├─ 数据强一致性
  └─ 无需人工介入
```

**优点**：
- 自动故障切换
- 数据强一致性
- 生产级高可用

**缺点**：
- 实现复杂
- 性能略有下降
- 至少需要 3 个节点

---

### 2. **主从同步原理**

#### 异步复制流程

```
Producer                Master                 Slave
   |                      |                      |
   |---1. Send Message--->|                      |
   |                      |---2. Write CommitLog-|
   |                      |                      |
   |<--3. Return Success--|                      |
   |                      |                      |
   |                      |---4. Async Replicate--->|
   |                      |                      |---5. Write CommitLog
   |                      |<--6. ACK (Optional)--|
```

**特点**：
- 写入 Master 后立即返回
- 异步复制到 Slave
- 性能高，但可能丢消息

#### 同步复制流程

```
Producer                Master                 Slave
   |                      |                      |
   |---1. Send Message--->|                      |
   |                      |---2. Write CommitLog-|
   |                      |                      |
   |                      |---3. Sync Replicate--->|
   |                      |                      |---4. Write CommitLog
   |                      |<--5. ACK-------------|
   |                      |                      |
   |<--6. Return Success--|                      |
```

**特点**：
- 等待 Slave ACK 后才返回
- 数据不丢失
- 性能略低

---

### 3. **Raft 协议简介**

Raft 是一种分布式一致性协议，用于实现自动故障切换。

#### 核心概念

**角色**：
- **Leader**：处理所有写请求，复制日志到 Follower
- **Follower**：接收 Leader 的日志，参与投票
- **Candidate**：选举过程中的临时角色

**任期（Term）**：
- 逻辑时钟，单调递增
- 每次选举开始新的 Term
- 用于检测过期的消息

**日志复制**：
- 所有写操作先写入 Leader 的日志
- Leader 复制日志到 Follower
- 多数派确认后，日志才被提交

#### Leader 选举流程

```
1. 初始状态：所有节点都是 Follower
   ├─ 等待 Leader 的心跳
   └─ 超时后转为 Candidate

2. 选举开始：
   ├─ Candidate 增加 Term
   ├─ 投票给自己
   ├─ 向其他节点请求投票
   └─ 等待投票结果

3. 投票规则：
   ├─ 每个节点每个 Term 只能投一票
   ├─ 先到先得（First-Come-First-Served）
   └─ Candidate 的日志必须至少和自己一样新

4. 选举结果：
   ├─ 获得多数派投票 → 成为 Leader
   ├─ 其他节点成为 Leader → 转为 Follower
   └─ 超时无结果 → 重新选举
```

#### 日志复制流程

```
1. Leader 接收写请求
   ├─ 追加到本地日志
   └─ 分配日志索引（LogIndex）

2. Leader 复制日志到 Follower
   ├─ 发送 AppendEntries RPC
   ├─ 包含：Term、LogIndex、日志内容
   └─ 等待 Follower 响应

3. Follower 处理日志
   ├─ 检查 Term 和 LogIndex
   ├─ 追加到本地日志
   └─ 返回 ACK

4. Leader 提交日志
   ├─ 收到多数派 ACK
   ├─ 标记日志为已提交（Committed）
   └─ 应用到状态机

5. Leader 通知 Follower
   ├─ 下次心跳携带 CommitIndex
   └─ Follower 应用已提交的日志
```

---

## 🐛 Buggy 版本：单机存储，无容灾

### 问题场景

电商系统的订单消息存储在单个 Broker 上，没有任何备份和容灾机制。

### Bug 列表

#### Bug 1: 单点故障

```java
// Buggy 实现
public class SingleBrokerStore {
    private final CommitLog commitLog;

    public PutMessageResult putMessage(Message message) {
        // Bug: 只写入本地，没有备份
        return commitLog.appendMessage(message);
    }
}
```

**问题**：
- Broker 宕机后，消息无法发送
- 磁盘故障后，消息永久丢失
- 无法提供高可用服务

#### Bug 2: 数据丢失

```java
// Buggy 实现
public class MessageStore {
    public PutMessageResult putMessage(Message message) {
        // Bug: 写入 PageCache 后立即返回，未刷盘
        commitLog.appendMessage(message);
        return new PutMessageResult(PutMessageStatus.PUT_OK);
    }
}
```

**问题**：
- 消息只在内存中，未持久化
- 进程崩溃后，消息丢失
- 无法保证消息可靠性

#### Bug 3: 无故障检测

```java
// Buggy 实现
public class BrokerController {
    public void start() {
        // Bug: 没有心跳检测
        // Bug: 没有健康检查
        // Bug: 无法感知节点故障
    }
}
```

**问题**：
- 节点宕机后，无法及时发现
- Producer/Consumer 仍然向故障节点发送请求
- 影响业务可用性

#### Bug 4: 无自动切换

```java
// Buggy 实现
public class HAService {
    private String masterAddress;

    public void onMasterDown() {
        // Bug: Master 宕机后，需要手动切换
        log.error("Master 宕机，请手动切换到 Slave");
    }
}
```

**问题**：
- 需要人工介入
- 恢复时间长（RTO 高）
- 影响业务连续性

#### Bug 5: 脑裂问题

```java
// Buggy 实现
public class HAService {
    public void electMaster() {
        // Bug: 没有多数派机制，可能出现多个 Master
        if (isMasterAlive()) {
            return;
        }

        // Bug: 网络分区时，可能同时选出多个 Master
        becomeMaster();
    }
}
```

**问题**：
- 网络分区时，可能出现多个 Master
- 数据不一致
- 消息重复或丢失

---

## ✅ Fixed 版本：完整的高可用架构

### 核心设计

#### 1. 主从同步服务

```java
/**
 * 高可用服务（主从同步）
 */
public class HAService {
    private final HAConnection haConnection;
    private final AtomicLong push2SlaveMaxOffset = new AtomicLong(0);

    /**
     * Master 端：推送数据到 Slave
     */
    public class HAConnection {
        private final SocketChannel socketChannel;
        private final SelectionKey selectionKey;
        private final ByteBuffer byteBufferRead = ByteBuffer.allocate(1024);
        private final ByteBuffer byteBufferWrite = ByteBuffer.allocate(1024 * 1024);

        /**
         * 传输数据到 Slave
         */
        public boolean transferData() throws Exception {
            // 1. 获取 Slave 的同步位置
            long slaveRequestOffset = byteBufferRead.getLong();

            // 2. 从 CommitLog 读取数据
            SelectMappedBufferResult result =
                commitLog.getData(slaveRequestOffset);

            if (result == null) {
                return true;
            }

            // 3. 构造传输数据
            // Header: masterOffset(8) + bodySize(4)
            byteBufferWrite.putLong(result.getStartOffset());
            byteBufferWrite.putInt(result.getSize());
            byteBufferWrite.put(result.getByteBuffer());

            // 4. 发送数据
            byteBufferWrite.flip();
            while (byteBufferWrite.hasRemaining()) {
                int writeSize = socketChannel.write(byteBufferWrite);
                if (writeSize == 0) {
                    break;
                }
            }

            // 5. 更新同步进度
            push2SlaveMaxOffset.set(result.getStartOffset() + result.getSize());

            return true;
        }
    }

    /**
     * Slave 端：从 Master 拉取数据
     */
    public class HAClient {
        private final SocketChannel socketChannel;
        private final AtomicLong currentReportedOffset = new AtomicLong(0);

        /**
         * 从 Master 拉取数据
         */
        public boolean processReadEvent() {
            // 1. 读取 Header
            long masterOffset = byteBufferRead.getLong();
            int bodySize = byteBufferRead.getInt();

            // 2. 读取 Body
            byte[] bodyData = new byte[bodySize];
            byteBufferRead.get(bodyData);

            // 3. 写入本地 CommitLog
            commitLog.appendData(masterOffset, bodyData);

            // 4. 更新同步位置
            currentReportedOffset.set(masterOffset + bodySize);

            // 5. 向 Master 报告进度
            reportSlaveMaxOffset();

            return true;
        }

        /**
         * 向 Master 报告同步进度
         */
        private void reportSlaveMaxOffset() {
            ByteBuffer buffer = ByteBuffer.allocate(8);
            buffer.putLong(currentReportedOffset.get());
            buffer.flip();
            socketChannel.write(buffer);
        }
    }
}
```

#### 2. 同步复制 vs 异步复制

```java
/**
 * 消息存储服务（支持同步/异步复制）
 */
public class MessageStore {
    private final HAService haService;
    private final BrokerConfig brokerConfig;

    /**
     * 存储消息
     */
    public PutMessageResult putMessage(Message message) {
        // 1. 写入 CommitLog
        AppendMessageResult result = commitLog.appendMessage(message);

        if (result.getStatus() != AppendMessageStatus.PUT_OK) {
            return new PutMessageResult(PutMessageStatus.PUT_FAILED, result);
        }

        // 2. 根据配置决定是否等待同步
        if (brokerConfig.getBrokerRole() == BrokerRole.SYNC_MASTER) {
            // 同步复制：等待 Slave ACK
            return handleSyncReplication(result);
        } else {
            // 异步复制：立即返回
            return new PutMessageResult(PutMessageStatus.PUT_OK, result);
        }
    }

    /**
     * 处理同步复制
     */
    private PutMessageResult handleSyncReplication(AppendMessageResult result) {
        long offset = result.getWroteOffset() + result.getWroteBytes();

        // 等待 Slave 同步（最多等待 5 秒）
        long beginTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - beginTime < 5000) {
            if (haService.getPush2SlaveMaxOffset() >= offset) {
                // Slave 已同步
                return new PutMessageResult(PutMessageStatus.PUT_OK, result);
            }

            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                break;
            }
        }

        // 超时，返回 SLAVE_NOT_AVAILABLE
        return new PutMessageResult(PutMessageStatus.SLAVE_NOT_AVAILABLE, result);
    }
}
```

#### 3. Dledger 模式（基于 Raft）

```java
/**
 * Dledger 存储（基于 Raft 协议）
 */
public class DledgerCommitLog {
    private final DledgerServer dledgerServer;

    /**
     * 追加消息（通过 Raft 协议）
     */
    public AppendEntryResponse appendMessage(Message message) {
        // 1. 序列化消息
        byte[] data = serialize(message);

        // 2. 通过 Raft 协议追加日志
        AppendEntryRequest request = new AppendEntryRequest();
        request.setBody(data);

        // 3. Leader 复制到多数派
        AppendEntryResponse response = dledgerServer.handleAppend(request);

        return response;
    }
}

/**
 * Dledger 服务器（Raft 实现）
 */
public class DledgerServer {
    private volatile DledgerLeaderElector leaderElector;
    private volatile DledgerEntryPusher entryPusher;
    private volatile MemberState memberState;

    /**
     * 处理追加请求
     */
    public AppendEntryResponse handleAppend(AppendEntryRequest request) {
        // 1. 检查是否为 Leader
        if (!memberState.isLeader()) {
            // 转发到 Leader
            return forwardToLeader(request);
        }

        // 2. 追加到本地日志
        DledgerEntry entry = new DledgerEntry();
        entry.setTerm(memberState.currTerm());
        entry.setIndex(dledgerStore.getLedgerEndIndex() + 1);
        entry.setBody(request.getBody());

        dledgerStore.appendAsLeader(entry);

        // 3. 复制到 Follower（异步）
        entryPusher.wakeup();

        // 4. 等待多数派确认
        return waitForQuorum(entry.getIndex());
    }

    /**
     * 等待多数派确认
     */
    private AppendEntryResponse waitForQuorum(long index) {
        long beginTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - beginTime < 3000) {
            // 检查是否达到多数派
            if (memberState.getAckIndex() >= index) {
                return AppendEntryResponse.success(index);
            }

            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                break;
            }
        }

        return AppendEntryResponse.timeout();
    }
}

/**
 * Leader 选举器
 */
public class DledgerLeaderElector {
    private final MemberState memberState;
    private final DledgerRpcService rpcService;

    /**
     * 发起选举
     */
    public void startElection() {
        // 1. 增加 Term
        long nextTerm = memberState.currTerm() + 1;
        memberState.setCurrTerm(nextTerm);

        // 2. 投票给自己
        memberState.setCurrVoteFor(memberState.getSelfId());

        // 3. 向其他节点请求投票
        List<CompletableFuture<VoteResponse>> futures = new ArrayList<>();
        for (String peerId : memberState.getPeerMap().keySet()) {
            VoteRequest request = new VoteRequest();
            request.setTerm(nextTerm);
            request.setLeaderId(memberState.getSelfId());
            request.setLedgerEndIndex(dledgerStore.getLedgerEndIndex());
            request.setLedgerEndTerm(dledgerStore.getLedgerEndTerm());

            CompletableFuture<VoteResponse> future =
                rpcService.vote(request, peerId);
            futures.add(future);
        }

        // 4. 等待投票结果
        int voteCount = 1; // 自己的票
        for (CompletableFuture<VoteResponse> future : futures) {
            try {
                VoteResponse response = future.get(3, TimeUnit.SECONDS);
                if (response.getVoteResult() == VoteResponse.RESULT.ACCEPT) {
                    voteCount++;
                }
            } catch (Exception e) {
                // 超时或异常，忽略
            }
        }

        // 5. 判断是否获得多数派
        int quorum = memberState.getPeerSize() / 2 + 1;
        if (voteCount >= quorum) {
            // 成为 Leader
            memberState.changeToLeader(nextTerm);
            log.info("成为 Leader，Term: {}", nextTerm);
        } else {
            // 选举失败，重新选举
            log.info("选举失败，重新选举");
        }
    }

    /**
     * 处理投票请求
     */
    public VoteResponse handleVote(VoteRequest request) {
        // 1. 检查 Term
        if (request.getTerm() < memberState.currTerm()) {
            return VoteResponse.reject(memberState.currTerm());
        }

        // 2. 检查是否已投票
        if (memberState.getCurrVoteFor() != null) {
            return VoteResponse.reject(memberState.currTerm());
        }

        // 3. 检查日志是否至少和自己一样新
        if (request.getLedgerEndTerm() < dledgerStore.getLedgerEndTerm()) {
            return VoteResponse.reject(memberState.currTerm());
        }

        if (request.getLedgerEndTerm() == dledgerStore.getLedgerEndTerm() &&
            request.getLedgerEndIndex() < dledgerStore.getLedgerEndIndex()) {
            return VoteResponse.reject(memberState.currTerm());
        }

        // 4. 投票
        memberState.setCurrVoteFor(request.getLeaderId());
        return VoteResponse.accept(memberState.currTerm());
    }
}
```

#### 4. 故障检测与自动切换

```java
/**
 * 故障检测服务
 */
public class FailureDetector {
    private final Map<String, Long> lastHeartbeatTime = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor();

    public void start() {
        // 定时检测心跳（每秒）
        scheduler.scheduleAtFixedRate(() -> {
            checkHeartbeat();
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * 检查心跳
     */
    private void checkHeartbeat() {
        long now = System.currentTimeMillis();

        for (Map.Entry<String, Long> entry : lastHeartbeatTime.entrySet()) {
            String brokerId = entry.getKey();
            long lastTime = entry.getValue();

            // 超过 10 秒没有心跳，认为节点故障
            if (now - lastTime > 10000) {
                log.warn("节点故障: {}", brokerId);
                onBrokerDown(brokerId);
            }
        }
    }

    /**
     * 处理节点故障
     */
    private void onBrokerDown(String brokerId) {
        if (memberState.getLeaderId().equals(brokerId)) {
            // Leader 故障，触发选举
            log.info("Leader 故障，触发选举");
            leaderElector.startElection();
        }
    }

    /**
     * 更新心跳时间
     */
    public void updateHeartbeat(String brokerId) {
        lastHeartbeatTime.put(brokerId, System.currentTimeMillis());
    }
}
```

---

## 🎯 性能对比测试

### 测试场景

- 3 个 Broker 节点
- 每条消息 1KB
- 发送 100 万条消息
- 模拟 Leader 宕机

### 测试结果

| 指标 | 单机 | 异步复制 | 同步复制 | Dledger |
|------|------|---------|---------|---------|
| 写入 TPS | 50,000 | 45,000 | 30,000 | 35,000 |
| 写入延迟 P99 | 10 ms | 15 ms | 30 ms | 25 ms |
| 数据可靠性 | ❌ 可能丢失 | ⚠️ 可能丢失 | ✅ 不丢失 | ✅ 不丢失 |
| 故障切换 | ❌ 手动 | ❌ 手动 | ❌ 手动 | ✅ 自动 |
| 切换时间 | - | - | - | < 10s |
| 脑裂防护 | ❌ 无 | ❌ 无 | ❌ 无 | ✅ 有 |

---

## 💡 架构思想的应用

### 1. CAP 理论

**CAP 定理**：
- **C (Consistency)**：一致性
- **A (Availability)**：可用性
- **P (Partition Tolerance)**：分区容错性

**在 RocketMQ 中的体现**：
- **异步复制**：AP（高可用，但可能丢消息）
- **同步复制**：CP（强一致，但可用性略低）
- **Dledger**：CP（强一致，自动切换）

### 2. 主从复制模式

**应用场景**：
- **MySQL 主从复制**：读写分离
- **Redis 主从复制**：高可用
- **Elasticsearch 主从复制**：数据冗余

### 3. Raft 协议

**应用场景**：
- **etcd**：分布式配置中心
- **Consul**：服务发现
- **TiKV**：分布式 KV 存储

---

## 🧪 测试指南

### 1. 主从同步测试

```bash
# 启动 Master
curl "http://localhost:8070/challenge/level14/startMaster"

# 启动 Slave
curl "http://localhost:8070/challenge/level14/startSlave"

# 发送消息
curl "http://localhost:8070/challenge/level14/sendMessage?count=1000"

# 查看同步进度
curl "http://localhost:8070/challenge/level14/syncProgress"
```

### 2. 故障切换测试

```bash
# 停止 Master（模拟故障）
curl "http://localhost:8070/challenge/level14/stopMaster"

# 查看是否自动切换
curl "http://localhost:8070/challenge/level14/leaderStatus"

# 验证数据完整性
curl "http://localhost:8070/challenge/level14/verifyData"
```

### 3. 性能对比测试

```bash
# 测试异步复制性能
curl "http://localhost:8070/challenge/level14/benchmark?mode=async"

# 测试同步复制性能
curl "http://localhost:8070/challenge/level14/benchmark?mode=sync"

# 测试 Dledger 性能
curl "http://localhost:8070/challenge/level14/benchmark?mode=dledger"
```

---

## 🎓 学习目标

完成本 Challenge 后，你应该能够：

### 理解层面
- ✅ 理解 CAP 理论在消息系统中的应用
- ✅ 理解主从同步的原理和权衡
- ✅ 理解 Raft 协议的核心思想
- ✅ 理解故障检测和自动切换机制

### 实践层面
- ✅ 能够实现主从同步机制
- ✅ 能够实现故障检测
- ✅ 能够实现简化版的 Raft 协议
- ✅ 能够进行高可用测试

### 应用层面
- ✅ 能够设计高可用的分布式系统
- ✅ 能够选择合适的复制策略
- ✅ 能够处理脑裂等边界情况

---

## 📖 扩展阅读

### RocketMQ 源码
- `org.apache.rocketmq.store.ha.HAService`
- `org.apache.rocketmq.store.dledger.DledgerCommitLog`

### Raft 协议
- [Raft 论文](https://raft.github.io/raft.pdf)
- [Raft 动画演示](http://thesecretlivesofdata.com/raft/)

### 相关技术
- etcd: 基于 Raft 的分布式 KV 存储
- Consul: 基于 Raft 的服务发现
- TiKV: 基于 Raft 的分布式存储

---

## 🎉 恭喜完成所有挑战！

你已经完成了从基础到架构的完整学习路径：
- **Level 1-6**：掌握 RocketMQ 的基本使用
- **Level 7-11**：掌握 RocketMQ 的高级特性
- **Level 12-14**：理解 RocketMQ 的架构设计

**下一步建议**：
1. 阅读 RocketMQ 源码，深入理解实现细节
2. 在生产环境中应用所学知识
3. 参与 RocketMQ 社区，贡献代码
4. 将架构思想应用到自己的系统设计中

---

**准备好深入理解 RocketMQ 的高可用架构了吗？** 🎯

开始实现你的高可用系统吧！
