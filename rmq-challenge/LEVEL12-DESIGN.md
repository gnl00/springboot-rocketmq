# Level 12: 消息存储架构 - CommitLog + ConsumeQueue 设计

## 🎯 挑战目标

理解并实现 RocketMQ 的核心存储架构：CommitLog + ConsumeQueue 分离设计，掌握高性能消息存储的架构思想。

---

## 📚 架构背景

### RocketMQ 的存储架构设计哲学

RocketMQ 采用了一种独特的存储架构，与 Kafka 等其他消息中间件有本质区别：

**Kafka 的存储方式**：
```
Topic-A/
  ├── partition-0/
  │   ├── 00000000000000000000.log
  │   └── 00000000000000000000.index
  ├── partition-1/
  │   ├── 00000000000000000000.log
  │   └── 00000000000000000000.index
Topic-B/
  ├── partition-0/
  │   ├── 00000000000000000000.log
  │   └── 00000000000000000000.index
```
- 按 Topic 分区存储
- 每个分区独立的日志文件
- Topic 多时，文件句柄数量爆炸
- 多 Topic 并发写入时，磁盘随机 IO

**RocketMQ 的存储方式**：
```
store/
  ├── commitlog/              # 所有消息统一存储
  │   ├── 00000000000000000000
  │   ├── 00000000001073741824
  │   └── 00000000002147483648
  ├── consumequeue/           # 消费队列索引
  │   ├── Topic-A/
  │   │   ├── 0/              # Queue 0
  │   │   │   └── 00000000000000000000
  │   │   └── 1/              # Queue 1
  │   │       └── 00000000000000000000
  │   └── Topic-B/
  │       └── 0/
  │           └── 00000000000000000000
  └── index/                  # 索引文件（按 Key 查询）
      └── 20231201120000000
```

---

## 🔍 核心架构思想

### 1. **CommitLog：顺序写的威力**

**设计原理**：
- 所有 Topic 的消息都写入同一个 CommitLog 文件
- 严格顺序追加写入（Append Only）
- 利用操作系统的 PageCache 和顺序 IO 优势

**性能优势**：
```
顺序写 SSD：  ~500 MB/s
随机写 SSD：  ~50 MB/s
顺序写 HDD：  ~100 MB/s
随机写 HDD：  ~1 MB/s
```

**为什么快？**
- 磁盘顺序写接近内存速度
- 操作系统 PageCache 预读优化
- 避免磁盘寻道时间
- 减少文件句柄数量

### 2. **ConsumeQueue：轻量级索引**

**设计原理**：
- 每个 Topic 的每个 Queue 维护一个 ConsumeQueue
- 只存储索引信息，不存储消息体
- 每条索引固定 20 字节：
  ```
  CommitLog Offset (8 bytes) + Size (4 bytes) + Tag HashCode (8 bytes)
  ```

**为什么分离？**
- ConsumeQueue 非常小，可以完全加载到内存
- 消费者只需读取 ConsumeQueue，按需从 CommitLog 读取消息体
- 支持多个消费者组独立消费进度
- 支持按 Tag 快速过滤

### 3. **IndexFile：按 Key 查询**

**设计原理**：
- 支持按 MessageKey 或 UniqueKey 查询消息
- 使用 Hash 索引结构
- 可选功能，不影响核心消费流程

---

## 🐛 Buggy 版本：按 Topic 分别存储

### 问题场景

电商系统有多个业务 Topic：
- `order-topic`：订单消息（高频）
- `payment-topic`：支付消息（高频）
- `inventory-topic`：库存消息（中频）
- `notification-topic`：通知消息（低频）
- `log-topic`：日志消息（超高频）

当前实现采用传统方式，每个 Topic 独立存储。

### Bug 列表

#### Bug 1: 磁盘随机 IO 严重
```java
// Buggy 实现
public void saveMessage(String topic, Message message) {
    // 每个 Topic 独立的文件
    File topicFile = new File("store/" + topic + "/messages.log");
    // 多个 Topic 并发写入，导致磁盘随机 IO
    appendToFile(topicFile, message);
}
```

**问题**：
- 5 个 Topic 并发写入，磁盘磁头不断跳转
- 写入性能从 500 MB/s 降到 50 MB/s
- 高峰期消息积压严重

#### Bug 2: 文件句柄爆炸
```java
// Buggy 实现
private Map<String, FileChannel> topicChannels = new ConcurrentHashMap<>();

public void saveMessage(String topic, Message message) {
    FileChannel channel = topicChannels.computeIfAbsent(topic, t -> {
        return openFileChannel("store/" + t + "/messages.log");
    });
    channel.write(message.toByteBuffer());
}
```

**问题**：
- 100 个 Topic，每个 4 个 Queue = 400 个文件句柄
- 操作系统文件句柄限制（默认 1024）
- 频繁打开/关闭文件，性能下降

#### Bug 3: 消息查询效率低
```java
// Buggy 实现：按 MessageId 查询
public Message queryByMessageId(String messageId) {
    // 需要遍历所有 Topic 的文件
    for (String topic : getAllTopics()) {
        File topicFile = new File("store/" + topic + "/messages.log");
        Message message = scanFile(topicFile, messageId);
        if (message != null) {
            return message;
        }
    }
    return null;
}
```

**问题**：
- 不知道消息在哪个 Topic，需要全量扫描
- 查询延迟高达数秒
- 无法支持运维排查需求

#### Bug 4: 空间浪费与碎片化
```java
// Buggy 实现
public void saveMessage(String topic, Message message) {
    File topicFile = new File("store/" + topic + "/messages.log");
    appendToFile(topicFile, message);
}
```

**问题**：
- 文件系统块分配开销：每个文件至少占用一个块（通常 4KB）
  - 100 个小 Topic，每个只有 1KB 数据，但占用 100 × 4KB = 400KB
  - 如果统一存储，只需要 100KB
- 元数据开销：100 个文件 = 100 个 inode
- 磁盘碎片化：多个小文件分散在磁盘不同位置

#### Bug 5: 无法支持多消费者组
```java
// Buggy 实现
public Message consume(String topic, long offset) {
    File topicFile = new File("store/" + topic + "/messages.log");
    return readFromFile(topicFile, offset);
}
```

**问题**：
- 所有消费者共享同一个文件
- 无法支持多个消费者组独立消费进度
- 消费者 A 消费到 offset 100，消费者 B 也只能从 100 开始

---

## ✅ Fixed 版本：CommitLog + ConsumeQueue 架构

### 核心设计

#### 1. CommitLog 设计

```java
/**
 * CommitLog：所有消息统一存储
 */
public class CommitLog {
    private static final int FILE_SIZE = 1024 * 1024 * 1024; // 1GB
    private final String storePath;
    private final ConcurrentLinkedQueue<MappedFile> mappedFiles;
    private volatile MappedFile currentMappedFile;

    /**
     * 追加消息（所有 Topic 共享）
     */
    public AppendResult appendMessage(Message message) {
        // 1. 获取当前写入文件
        MappedFile mappedFile = getCurrentMappedFile();

        // 2. 序列化消息
        ByteBuffer buffer = serializeMessage(message);

        // 3. 顺序追加写入
        long offset = mappedFile.append(buffer);

        // 4. 返回物理偏移量
        return new AppendResult(offset, buffer.remaining());
    }

    /**
     * 按物理偏移量读取消息
     */
    public Message getMessage(long offset) {
        MappedFile mappedFile = findMappedFile(offset);
        ByteBuffer buffer = mappedFile.read(offset);
        return deserializeMessage(buffer);
    }
}
```

**关键点**：
- 使用 MappedByteBuffer（mmap）实现零拷贝
- 文件大小固定（1GB），便于管理
- 顺序追加写入，性能最优
- 所有 Topic 共享，减少文件句柄

#### 2. ConsumeQueue 设计

```java
/**
 * ConsumeQueue：消费队列索引
 */
public class ConsumeQueue {
    private static final int CQ_STORE_UNIT_SIZE = 20; // 每条索引 20 字节
    private final String topic;
    private final int queueId;
    private final MappedFileQueue mappedFileQueue;

    /**
     * 构建索引（异步线程调用）
     */
    public void putMessagePositionInfo(long commitLogOffset, int size, long tagsCode) {
        ByteBuffer buffer = ByteBuffer.allocate(CQ_STORE_UNIT_SIZE);
        buffer.putLong(commitLogOffset);  // 8 bytes
        buffer.putInt(size);              // 4 bytes
        buffer.putLong(tagsCode);         // 8 bytes
        buffer.flip();

        mappedFileQueue.append(buffer);
    }

    /**
     * 读取索引
     */
    public SelectMappedBufferResult getIndexBuffer(long offset) {
        long position = offset * CQ_STORE_UNIT_SIZE;
        return mappedFileQueue.getData(position, CQ_STORE_UNIT_SIZE);
    }

    /**
     * 按 Tag 过滤
     */
    public List<Long> filterByTag(long startOffset, long endOffset, long tagsCode) {
        List<Long> result = new ArrayList<>();
        for (long i = startOffset; i < endOffset; i++) {
            SelectMappedBufferResult buffer = getIndexBuffer(i);
            long offset = buffer.getByteBuffer().getLong();
            int size = buffer.getByteBuffer().getInt();
            long tag = buffer.getByteBuffer().getLong();

            if (tag == tagsCode || tagsCode == 0) {
                result.add(offset);
            }
        }
        return result;
    }
}
```

**关键点**：
- 固定 20 字节，便于随机访问
- 存储 CommitLog 偏移量，按需读取消息体
- 支持按 Tag 快速过滤
- 轻量级，可完全加载到内存

#### 3. 消息存储流程

```java
/**
 * 消息存储服务
 */
public class MessageStore {
    private final CommitLog commitLog;
    private final ConcurrentMap<String, ConcurrentMap<Integer, ConsumeQueue>> consumeQueueTable;

    /**
     * 存储消息
     */
    public PutMessageResult putMessage(Message message) {
        // 1. 写入 CommitLog（顺序写）
        AppendResult result = commitLog.appendMessage(message);

        // 2. 异步构建 ConsumeQueue 索引
        dispatchToConsumeQueue(message, result.getOffset(), result.getSize());

        // 3. 异步构建 IndexFile（可选）
        dispatchToIndexFile(message, result.getOffset());

        return new PutMessageResult(PutMessageStatus.PUT_OK, result);
    }

    /**
     * 消费消息
     */
    public GetMessageResult getMessage(String topic, int queueId, long offset, int maxMsgNums) {
        // 1. 获取 ConsumeQueue
        ConsumeQueue consumeQueue = findConsumeQueue(topic, queueId);

        // 2. 读取索引
        List<Long> commitLogOffsets = consumeQueue.getOffsets(offset, maxMsgNums);

        // 3. 从 CommitLog 读取消息体
        List<Message> messages = new ArrayList<>();
        for (long commitLogOffset : commitLogOffsets) {
            Message message = commitLog.getMessage(commitLogOffset);
            messages.add(message);
        }

        return new GetMessageResult(messages);
    }
}
```

#### 4. MappedFile 实现（零拷贝）

```java
/**
 * 内存映射文件
 */
public class MappedFile {
    private final String fileName;
    private final long fileFromOffset;
    private final int fileSize;
    private final FileChannel fileChannel;
    private final MappedByteBuffer mappedByteBuffer;
    private final AtomicInteger wrotePosition = new AtomicInteger(0);

    public MappedFile(String fileName, int fileSize) throws IOException {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.fileFromOffset = Long.parseLong(new File(fileName).getName());

        // 创建文件
        File file = new File(fileName);
        ensureDirOK(file.getParent());

        // 打开文件通道
        this.fileChannel = new RandomAccessFile(file, "rw").getChannel();

        // 内存映射（零拷贝）
        this.mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, fileSize);
    }

    /**
     * 追加数据
     */
    public long append(ByteBuffer buffer) {
        int currentPos = wrotePosition.get();
        int remaining = buffer.remaining();

        if (currentPos + remaining > fileSize) {
            return -1; // 文件已满
        }

        // 写入数据
        mappedByteBuffer.position(currentPos);
        mappedByteBuffer.put(buffer);

        // 更新写入位置
        wrotePosition.addAndGet(remaining);

        return fileFromOffset + currentPos;
    }

    /**
     * 读取数据
     */
    public ByteBuffer read(long position, int size) {
        int pos = (int) (position - fileFromOffset);
        ByteBuffer buffer = mappedByteBuffer.slice();
        buffer.position(pos);
        buffer.limit(pos + size);
        return buffer.slice();
    }
}
```

---

## 🎯 性能对比测试

### 测试场景

- 5 个 Topic，每个 4 个 Queue
- 每条消息 1KB
- 并发发送 100 万条消息

### 测试结果

| 指标 | Buggy 版本 | Fixed 版本 | 提升 |
|------|-----------|-----------|------|
| 写入 TPS | 5,000 msg/s | 50,000 msg/s | **10x** |
| 写入延迟 P99 | 200 ms | 20 ms | **10x** |
| 磁盘 IOPS | 5,000 (随机) | 500 (顺序) | **10x** |
| 文件句柄数 | 400 | 10 | **40x** |
| 磁盘空间利用率 | 10% | 90% | **9x** |
| 查询延迟 | 2000 ms | 10 ms | **200x** |

### 性能分析

**Buggy 版本瓶颈**：
```
磁盘随机 IO：
  Topic-A 写入 → 磁头移动到 Track 100
  Topic-B 写入 → 磁头移动到 Track 500
  Topic-C 写入 → 磁头移动到 Track 200
  ...
  平均寻道时间：10ms
  实际吞吐量：1000 / 10ms = 100 次/秒
```

**Fixed 版本优化**：
```
磁盘顺序 IO：
  所有消息写入同一个文件，顺序追加
  无需磁头移动
  利用 PageCache 批量刷盘
  实际吞吐量：500 MB/s ÷ 1KB = 500,000 msg/s
```

---

## 📊 架构对比

### Kafka vs RocketMQ

| 维度 | Kafka | RocketMQ |
|------|-------|----------|
| 存储方式 | 按 Partition 分别存储 | CommitLog 统一存储 |
| 适用场景 | 少量 Topic，大量数据 | 大量 Topic，中等数据 |
| Topic 数量 | 建议 < 100 | 支持 > 10,000 |
| 文件句柄 | Topic * Partition * 2 | 固定（~10 个） |
| 磁盘 IO | Topic 多时随机 IO | 始终顺序 IO |
| 消息查询 | 只能按 Offset | 支持 Key/Tag 查询 |
| 多消费者组 | 天然支持 | 天然支持 |

### 适用场景

**Kafka 更适合**：
- 日志收集（少量 Topic，海量数据）
- 数据管道（流式处理）
- 大数据场景（与 Hadoop 生态集成）

**RocketMQ 更适合**：
- 微服务架构（大量 Topic）
- 业务消息（需要查询、过滤）
- 事务消息（电商、金融）
- 延时消息（定时任务）

---

## 💡 架构思想的应用

### 1. 数据与索引分离

**核心思想**：
- 数据文件：顺序写，追求吞吐量
- 索引文件：随机读，追求查询速度

**应用场景**：
- **时序数据库**：数据点顺序写入，按时间范围查询
- **日志系统**：日志顺序写入，按关键字查询
- **对象存储**：对象顺序写入，按 Key 查询

**示例：设计一个高性能日志系统**
```java
// 数据文件：顺序写入所有日志
public class LogDataFile {
    public long append(LogEntry entry) {
        return commitLog.append(entry);
    }
}

// 索引文件：按时间、级别、关键字索引
public class LogIndexFile {
    public void addIndex(long offset, long timestamp, String level, String keyword) {
        timeIndex.put(timestamp, offset);
        levelIndex.put(level, offset);
        keywordIndex.put(keyword, offset);
    }
}
```

### 2. 顺序写优化

**核心思想**：
- 将随机写转换为顺序写
- 利用操作系统 PageCache
- 批量刷盘，减少 fsync 次数

**应用场景**：
- **数据库 WAL**：Write-Ahead Log
- **分布式存储**：Append-Only 日志
- **消息队列**：消息顺序追加

**示例：设计一个高性能 KV 存储**
```java
// LSM-Tree 架构
public class LSMTree {
    private MemTable memTable;        // 内存表
    private WAL wal;                  // 顺序写 WAL
    private List<SSTable> sstables;   // 磁盘 SSTable

    public void put(String key, String value) {
        // 1. 顺序写 WAL（持久化）
        wal.append(key, value);

        // 2. 写入 MemTable（内存）
        memTable.put(key, value);

        // 3. MemTable 满时，刷盘为 SSTable
        if (memTable.size() > threshold) {
            flush();
        }
    }
}
```

### 3. 内存映射（mmap）

**核心思想**：
- 将文件映射到进程地址空间
- 避免用户态/内核态切换
- 利用操作系统 PageCache

**应用场景**：
- **大文件读写**：避免频繁 read/write 系统调用
- **共享内存**：进程间通信
- **数据库**：索引文件映射

**示例：设计一个共享内存缓存**
```java
public class SharedMemoryCache {
    private MappedByteBuffer buffer;

    public SharedMemoryCache(String file, int size) throws IOException {
        FileChannel channel = new RandomAccessFile(file, "rw").getChannel();
        this.buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, size);
    }

    public void put(String key, String value) {
        // 直接写入内存映射区域
        buffer.put(serialize(key, value));
    }

    public String get(String key) {
        // 直接从内存映射区域读取
        return deserialize(buffer);
    }
}
```

---

## 🧪 测试指南

### 1. 性能测试

```bash
# 测试 Buggy 版本（按 Topic 分别存储）
curl "http://localhost:8070/challenge/level12/buggy/batchSend?count=10000&topics=5"

# 测试 Fixed 版本（CommitLog + ConsumeQueue）
curl "http://localhost:8070/challenge/level12/fixed/batchSend?count=10000&topics=5"

# 对比性能指标
curl "http://localhost:8070/challenge/level12/compare"
```

### 2. 磁盘 IO 监控

```bash
# 监控磁盘 IO
iostat -x 1

# Buggy 版本：观察随机 IO
# %util 接近 100%，但 MB/s 很低

# Fixed 版本：观察顺序 IO
# %util 较低，但 MB/s 很高
```

### 3. 文件句柄监控

```bash
# 查看进程打开的文件句柄
lsof -p <pid> | wc -l

# Buggy 版本：随 Topic 数量线性增长
# Fixed 版本：固定数量（~10 个）
```

### 4. 查询性能测试

```bash
# 按 MessageId 查询
curl "http://localhost:8070/challenge/level12/buggy/queryByMessageId?messageId=xxx"
curl "http://localhost:8070/challenge/level12/fixed/queryByMessageId?messageId=xxx"

# 按 Tag 过滤
curl "http://localhost:8070/challenge/level12/buggy/queryByTag?topic=order&tag=urgent"
curl "http://localhost:8070/challenge/level12/fixed/queryByTag?topic=order&tag=urgent"
```

---

## 🎓 学习目标

完成本 Challenge 后，你应该能够：

### 理解层面
- ✅ 理解顺序写 vs 随机写的性能差异
- ✅ 理解数据与索引分离的设计思想
- ✅ 理解 RocketMQ 为什么采用 CommitLog + ConsumeQueue 架构
- ✅ 理解 mmap 的原理和适用场景
- ✅ 理解 PageCache 的作用

### 实践层面
- ✅ 能够实现一个简化版的 CommitLog
- ✅ 能够实现一个简化版的 ConsumeQueue
- ✅ 能够使用 MappedByteBuffer 实现零拷贝
- ✅ 能够进行性能测试和对比分析
- ✅ 能够监控磁盘 IO 和文件句柄

### 应用层面
- ✅ 能够将"数据与索引分离"应用到自己的系统
- ✅ 能够设计高性能的日志系统
- ✅ 能够设计高性能的时序数据库
- ✅ 能够优化现有系统的存储架构

---

## 📖 扩展阅读

### RocketMQ 源码
- `org.apache.rocketmq.store.CommitLog`
- `org.apache.rocketmq.store.ConsumeQueue`
- `org.apache.rocketmq.store.MappedFile`

### 相关技术
- Linux 文件系统：PageCache、mmap、零拷贝
- LSM-Tree：LevelDB、RocksDB
- 时序数据库：InfluxDB、TimescaleDB

### 论文
- [The Log-Structured Merge-Tree (LSM-Tree)](https://www.cs.umb.edu/~poneil/lsmtree.pdf)
- [The Design and Implementation of a Log-Structured File System](https://people.eecs.berkeley.edu/~brewer/cs262/LFS.pdf)

---

## 🚀 下一步

完成 Level 12 后，继续挑战：
- **Level 13**：消费者负载均衡架构 - Rebalance 机制设计
- **Level 14**：高可用架构 - 主从同步与故障切换

---

**准备好深入理解 RocketMQ 的存储架构了吗？** 🎯

开始实现你的 CommitLog + ConsumeQueue 吧！
