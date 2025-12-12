# Level 12: 消息存储架构 - CommitLog + ConsumeQueue 设计

## 疑问

### 1 日志文件数量问题

> 其中有一个 Bug 原因说“每个 Topic 独立文件”，如果所有的 topic 都放到同一个 commitlog 中这种实现我认为也不太好，
> 因为每次 write 的时候不论是哪一个 topic 都需要拿到锁 lock 一下，这样子写的效率不高；
> 如果一个 topic 下分多个 queue，将同一个 topic 下的消息存到不同的 messagequeue 中，那问题不是回到了之前的“每个 Topic 独立文件文件句柄爆炸” 这个问题了吗

```shell
1. 统一 CommitLog 的问题：所有 topic 写入同一个文件，每次写都要加全局锁，写入效率不高
2. 分 Queue 的问题：如果一个 topic 下分多个 queue，每个 queue 独立文件，不就又回到"文件句柄爆炸"的问题了吗？

关键点分析

1. 全局锁的性能影响其实没那么大

看 Fixed 版本的代码（Level12MessageStoreFixed.java:85-108），确实有个 commitLogLock，但要注意：

- 锁的粒度很小：只锁住了"追加写入"这个操作
- 顺序写极快：在 SSD 上顺序写 ~500 MB/s，单次写入可能只需要几微秒
- 锁竞争时间短：相比磁盘随机 IO（需要毫秒级），锁等待时间可以忽略不计

性能对比：
Buggy 版本（多文件随机 IO）：
- 写入延迟：~10-50ms（磁盘随机寻道）
- 吞吐量：~5,000 TPS

Fixed 版本（单文件顺序 IO + 全局锁）：
- 写入延迟：~0.1-1ms（顺序写 + 锁等待）
- 吞吐量：~50,000 TPS

2. RocketMQ 的实际设计：CommitLog + ConsumeQueue

RocketMQ 的真实架构是这样的：

写入流程：
所有消息 → 统一 CommitLog（1个文件，顺序写）
             ↓
        异步构建索引
             ↓
   ConsumeQueue（每个 Topic-Queue 一个，轻量级）

关键点：
- CommitLog：所有消息混在一起存，只有 1 个文件（或按大小切分成多个 1GB 文件）
- ConsumeQueue：每个 Topic-Queue 一个索引文件，但只存索引（20 字节/条）

3. 为什么 ConsumeQueue 不会导致文件句柄爆炸？

这是个很好的问题！答案是：

场景对比：

| 维度         | Buggy（每 Topic 一个数据文件） | Fixed（CommitLog + ConsumeQueue）               |
|--------------|--------------------------------|-------------------------------------------------|
| 数据文件     | 100 个 Topic = 100 个文件      | 1 个 CommitLog                                  |
| 索引文件     | 无                             | 100 个 Topic × 4 个 Queue = 400 个 ConsumeQueue |
| 写入方式     | 100 个文件随机写               | 1 个文件顺序写 + 400 个索引文件顺序写           |
| 单次写入大小 | 数据文件：~1KB                 | CommitLog：~1KBConsumeQueue：20 字节            |
| 文件句柄压力 | 高（数据文件频繁读写）         | 低（ConsumeQueue 可以批量写、延迟写）           |

核心差异：
1. ConsumeQueue 是轻量级的：每条只有 20 字节（CommitLog Offset 8字节 + Size 4字节 + TagHashCode 8字节）
2. ConsumeQueue 可以批量写：积累一批索引再一次性写入，减少 IO 次数
3. ConsumeQueue 可以延迟写：不需要实时刷盘，可以利用 PageCache
4. ConsumeQueue 读取是顺序的：消费者按顺序读，命中 PageCache 概率高

4. 你的担心在什么场景下会成立？

如果是这样设计，确实会有问题：

// ❌ 错误设计：每个 Queue 存完整消息
Topic A - Queue 0 → 完整消息文件
Topic A - Queue 1 → 完整消息文件
Topic B - Queue 0 → 完整消息文件
...

这样确实会回到"文件句柄爆炸"的问题。

但 RocketMQ 的设计是：

// ✅ 正确设计：数据与索引分离
CommitLog → 所有消息（1个文件）
Topic A - Queue 0 → 索引（20字节/条）
Topic A - Queue 1 → 索引（20字节/条）
Topic B - Queue 0 → 索引（20字节/条）
...

5. 实际的性能权衡

RocketMQ 的设计哲学是：

1. 写入路径：牺牲一点点锁竞争，换取磁盘顺序 IO（收益巨大）
2. 读取路径：通过 ConsumeQueue 索引，避免扫描 CommitLog（收益巨大）
3. 文件句柄：ConsumeQueue 虽然多，但都是轻量级的，可以按需打开/关闭

总结

你的担心是有道理的，但 RocketMQ 通过数据与索引分离巧妙地解决了这个矛盾：

1. 全局锁的影响 << 顺序 IO 的收益（10x-100x 性能提升）
2. ConsumeQueue 虽然多，但轻量级（只存 20 字节索引，不存消息体）
3. ConsumeQueue 可以批量写、延迟写（减少实际的文件操作）

这就是为什么 RocketMQ 在高吞吐场景下比 Kafka 更有优势的原因之一！
```

### 2 文件大小判断

> one.demo.challenge.level12.MappedFileQueue#load 中的 if (file.length() == mappedFileSize) 这个文件大小判断

```shell
1. 过滤未完成写入的文件

在实际运行中，可能会出现以下异常情况：

// 场景 1：程序崩溃
MappedFile file = new MappedFile("00000000000000000000", 1GB);
file.appendMessage(data);  // 写入了一部分数据
// 💥 程序突然崩溃，文件只有 500MB

// 场景 2：磁盘空间不足
MappedFile file = new MappedFile("00000000001073741824", 1GB);
// ❌ 磁盘空间不足，文件创建失败或不完整

如果加载这些不完整的文件，会导致：
- 数据错乱（偏移量计算错误）
- 程序崩溃（读取越界）
- 索引不一致

2. 确保文件完整性

RocketMQ 的 MappedFile 在创建时会预分配完整的文件大小：

// MappedFile 构造函数中
RandomAccessFile raf = new RandomAccessFile(file, "rw");
FileChannel channel = raf.getChannel();

// 预分配 1GB 空间（即使还没写入数据）
MappedByteBuffer buffer = channel.map(
   FileChannel.MapMode.READ_WRITE,
   0,
   1GB  // 预分配完整大小
);

正常情况下：
- 文件创建成功 → file.length() == 1GB
- 即使只写入了 1KB，文件大小也是 1GB（预分配）

异常情况下：
- 文件创建失败 → file.length() < 1GB
- 程序崩溃时正在创建 → file.length() == 0 或部分大小

3. 避免加载临时文件或脏数据

目录中可能存在其他文件：

store/commitlog/
├── 00000000000000000000      # 1GB - 正常文件 ✅
├── 00000000001073741824      # 1GB - 正常文件 ✅
├── 00000000002147483648      # 500MB - 未完成 ❌
├── .tmp_123456               # 临时文件 ❌
└── checkpoint                # 检查点文件 ❌

通过 file.length() == mappedFileSize 可以：
- 只加载完整的 MappedFile
- 跳过临时文件、检查点文件等
- 避免加载损坏的文件

4. 实际案例对比

没有文件大小检查的问题：

// ❌ 不检查文件大小
private void load() {
   for (File file : files) {
       // 直接加载所有文件
       MappedFile mf = new MappedFile(file.getPath(), mappedFileSize);
       mappedFiles.add(mf);
   }
}

// 问题：
// 1. 加载了一个 500MB 的不完整文件
// 2. 计算偏移量时：offset = fileFromOffset + position
//    假设 fileFromOffset = 1GB，但文件实际只有 500MB
// 3. 读取时：position = 600MB → 越界！💥

有文件大小检查的好处：

// ✅ 检查文件大小
private void load() {
   for (File file : files) {
       if (file.length() == mappedFileSize) {  // 只加载完整文件
           MappedFile mf = new MappedFile(file.getPath(), mappedFileSize);
           mappedFiles.add(mf);
       } else {
           log.warn("⚠️ 跳过不完整的文件: {}, size: {}, expected: {}",
               file.getName(), file.length(), mappedFileSize);
           // 可以选择删除或重命名这个文件
       }
   }
}

5. RocketMQ 的实际处理策略

在 RocketMQ 的真实实现中，对于不完整的文件有更复杂的处理：

// RocketMQ 源码逻辑（简化版）
private void load() {
   for (File file : files) {
       if (file.length() == mappedFileSize) {
           // 完整文件：正常加载
           mappedFiles.add(new MappedFile(file));} else if (file.length() < mappedFileSize) {
           // 不完整文件：可能是最后一个文件（正在写入）
           if (isLastFile(file)) {
               // 恢复最后一个文件
               mappedFiles.add(new MappedFile(file));log.info("恢复最后一个文件: {}", file.getName());
           } else {
               // 中间文件损坏：严重错误
               log.error("❌ 文件损坏: {}", file.getName());
               // 可能需要人工介入修复
           }
       }
   }
}

6. 改进建议

我们的实现可以更完善一些：

private void load() {
   File dir = new File(storePath);
   File[] files = dir.listFiles();

   if (files != null && files.length > 0) {
       Arrays.sort(files, (f1, f2) -> {
           long offset1 = Long.parseLong(f1.getName());
           long offset2 = Long.parseLong(f2.getName());
           return Long.compare(offset1, offset2);
       });

       for (int i = 0; i < files.length; i++) {
           File file = files[i];
           boolean isLastFile = (i == files.length - 1);

           if (file.length() == mappedFileSize) {
               // 完整文件：正常加载
               try {
                   MappedFile mappedFile = new MappedFile(file.getPath(), mappedFileSize);
                   mappedFiles.add(mappedFile);
                   log.info("📂 加载 MappedFile: {}", file.getName());
               } catch (IOException e) {
                   log.error("❌ 加载 MappedFile 失败: {}", file.getName(), e);
               }
           } else if (file.length() < mappedFileSize && isLastFile) {
               // 最后一个文件可能正在写入，尝试恢复
               log.warn("⚠️ 最后一个文件不完整，尝试恢复: {}, size: {}",
                   file.getName(), file.length());
               try {
                   MappedFile mappedFile = new MappedFile(file.getPath(), mappedFileSize);
                   mappedFiles.add(mappedFile);
               } catch (IOException e) {
                   log.error("❌ 恢复文件失败: {}", file.getName(), e);
               }
           } else {
               // 中间文件损坏或大小不对
               log.error("❌ 跳过异常文件: {}, size: {}, expected: {}",
                   file.getName(), file.length(), mappedFileSize);
               // 可以选择重命名为 .bad 文件
               // file.renameTo(new File(file.getPath() + ".bad"));
           }
       }

       if (!mappedFiles.isEmpty()) {
           currentWriteMappedFile = mappedFiles.get(mappedFiles.size() - 1);
       }
   }
}

总结

if (file.length() == mappedFileSize) 这个判断的核心用意是：

1. 保证数据完整性：只加载完整的文件
2. 避免程序崩溃：跳过损坏的文件
3. 简化偏移量计算：所有文件大小一致，便于计算
4. 过滤无关文件：只加载符合规格的 MappedFile
```

> 对应 RocketMQ 源码中的位置 `org.apache.rocketmq.store.MappedFileQueue#doLoad`

## 🎯 挑战目标

理解并实现 RocketMQ 的核心存储架构：CommitLog + ConsumeQueue 分离设计，掌握高性能消息存储的架构思想。

---

## 📚 背景知识

### RocketMQ vs Kafka 存储架构

**Kafka 的存储方式**：
- 按 Topic 分区存储
- 每个分区独立的日志文件
- Topic 多时，文件句柄数量爆炸
- 多 Topic 并发写入时，磁盘随机 IO

**RocketMQ 的存储方式**：
- 所有消息统一写入 CommitLog（顺序写）
- 每个 Topic 维护轻量级的 ConsumeQueue 索引
- 文件句柄数量固定
- 始终保持磁盘顺序 IO

### 为什么顺序写这么快？

```
顺序写 SSD：  ~500 MB/s
随机写 SSD：  ~50 MB/s  (慢 10 倍)
顺序写 HDD：  ~100 MB/s
随机写 HDD：  ~1 MB/s   (慢 100 倍)
```

---

## 🐛 Buggy 版本：按 Topic 分别存储

### 问题描述

当前实现采用传统方式，每个 Topic 独立存储消息文件。

### Bug 列表

#### Bug 1: 磁盘随机 IO 严重
- 多个 Topic 并发写入，磁盘磁头不断跳转
- 写入性能从 500 MB/s 降到 50 MB/s
- 高峰期消息积压严重

#### Bug 2: 文件句柄爆炸
- 每个 Topic 独立文件
- 100 个 Topic = 100 个文件句柄
- 超过操作系统限制（默认 1024）

#### Bug 3: 消息查询效率低
- 按 MessageId 查询需要遍历所有 Topic 文件
- 查询延迟高达数秒
- 无法支持运维排查需求

#### Bug 4: 空间浪费与碎片化
- 文件系统块分配开销（每个文件至少占用 4KB）
- 小 Topic 浪费空间（1KB 数据占用 4KB 磁盘）
- 元数据开销（每个文件一个 inode）
- 磁盘碎片化（文件分散存储）

#### Bug 5: 无法支持多消费者组
- 所有消费者共享同一个文件
- 无法独立维护消费进度

---

## 🧪 快速测试

### 1. 查看帮助信息

```bash
curl "http://localhost:8070/challenge/level12/help"
```

### 2. 发送单条消息

```bash
curl "http://localhost:8070/challenge/level12/buggy/sendMessage?topic=level12-order-topic&tag=urgent&key=ORDER-001&body=test"
```

### 3. 批量发送（观察随机 IO 问题）

```bash
# 发送 1000 条消息，分布在 5 个 Topic
curl "http://localhost:8070/challenge/level12/buggy/batchSend?count=1000&topics=5"
```

**观察现象**：
- 文件句柄数 = Topic 数量
- 平均写入延迟较高
- 吞吐量远低于理论值

### 4. 并发写入测试（观察性能下降）

```bash
# 10 个线程并发写入 5000 条消息
curl "http://localhost:8070/challenge/level12/buggy/concurrentWrite?count=5000&threads=10"
```

**观察现象**：
- 并发写入时，磁盘随机 IO 更加严重
- 吞吐量只有理论值的 10-20%
- 性能损失高达 80-90%

### 5. 按 MessageId 查询（观察查询慢）

```bash
# 先发送一条消息，记录 MessageId
curl "http://localhost:8070/challenge/level12/buggy/sendMessage?topic=level12-order-topic&tag=urgent&key=ORDER-001&body=test"

# 然后查询（需要遍历所有 Topic 文件）
curl "http://localhost:8070/challenge/level12/buggy/queryByMessageId?messageId=<your-message-id>"
```

**观察现象**：
- 查询延迟高达数秒
- 需要扫描所有 Topic 文件
- 生产环境不可接受

### 6. 按 Tag 过滤（观察扫描慢）

```bash
curl "http://localhost:8070/challenge/level12/buggy/queryByTag?topic=level12-order-topic&tag=urgent"
```

**观察现象**：
- 需要扫描整个 Topic 文件
- 在内存中过滤，效率低
- 无法支持大量消息的过滤

### 7. 查看统计信息

```bash
curl "http://localhost:8070/challenge/level12/buggy/stats"
```

**关注指标**：
- 文件句柄数（随 Topic 增加）
- 平均写入延迟（随机 IO 导致）
- 平均查询延迟（遍历文件导致）
- 磁盘空间利用率（预分配导致浪费）

### 8. 重置测试

```bash
curl "http://localhost:8070/challenge/level12/buggy/reset"
```

---

## 💡 任务目标

### 第一步：理解问题

1. 运行上述测试，观察 Buggy 版本的问题
2. 分析为什么会出现这些问题
3. 理解顺序写 vs 随机写的性能差异

### 第二步：设计方案

参考 `LEVEL12-DESIGN.md` 设计文档，思考：

1. **CommitLog 设计**
   - 如何实现所有消息统一存储？
   - 如何保证顺序追加写入？
   - 如何使用 MappedByteBuffer 实现零拷贝？

2. **ConsumeQueue 设计**
   - 如何设计轻量级索引？
   - 每条索引存储哪些信息？
   - 如何支持按 Tag 快速过滤？

3. **消息存储流程**
   - 写入 CommitLog
   - 异步构建 ConsumeQueue 索引
   - 异步构建 IndexFile（可选）

4. **消息消费流程**
   - 读取 ConsumeQueue 获取偏移量
   - 从 CommitLog 读取消息体
   - 按需加载，减少 IO

### 第三步：实现 Fixed 版本

创建以下类：

1. **CommitLog.java**
   - 统一存储所有消息
   - 使用 MappedFile 实现零拷贝
   - 顺序追加写入

2. **ConsumeQueue.java**
   - 轻量级索引（每条 20 字节）
   - 存储：CommitLog Offset + Size + Tag HashCode
   - 支持按 Tag 快速过滤

3. **MappedFile.java**
   - 内存映射文件
   - 零拷贝读写
   - 固定大小（1GB）

4. **MessageStoreFixed.java**
   - 整合 CommitLog 和 ConsumeQueue
   - 实现完整的存储流程
   - 提供查询接口

### 第四步：性能对比

实现 Fixed 版本后，运行相同的测试，对比：

| 指标 | Buggy 版本 | Fixed 版本 | 提升 |
|------|-----------|-----------|------|
| 写入 TPS | ~5,000 | ~50,000 | 10x |
| 写入延迟 P99 | ~200 ms | ~20 ms | 10x |
| 文件句柄数 | 100+ | ~10 | 10x |
| 查询延迟 | ~2000 ms | ~10 ms | 200x |

---

## 📖 参考资料

### 设计文档
- `LEVEL12-DESIGN.md` - 完整的架构设计文档

### RocketMQ 源码
- `org.apache.rocketmq.store.CommitLog`
- `org.apache.rocketmq.store.ConsumeQueue`
- `org.apache.rocketmq.store.MappedFile`

### 相关技术
- Linux 文件系统：PageCache、mmap、零拷贝
- LSM-Tree：LevelDB、RocksDB
- 时序数据库：InfluxDB、TimescaleDB

---

## 🎓 学习目标

完成本 Challenge 后，你应该能够：

### 理解层面
- ✅ 理解顺序写 vs 随机写的性能差异
- ✅ 理解数据与索引分离的设计思想
- ✅ 理解 RocketMQ 为什么采用 CommitLog + ConsumeQueue 架构
- ✅ 理解 mmap 的原理和适用场景

### 实践层面
- ✅ 能够实现一个简化版的 CommitLog
- ✅ 能够实现一个简化版的 ConsumeQueue
- ✅ 能够使用 MappedByteBuffer 实现零拷贝
- ✅ 能够进行性能测试和对比分析

### 应用层面
- ✅ 能够将"数据与索引分离"应用到自己的系统
- ✅ 能够设计高性能的日志系统
- ✅ 能够设计高性能的时序数据库
- ✅ 能够优化现有系统的存储架构

---

## 💬 提示

如果你在实现过程中遇到困难，可以：

1. 查看 `LEVEL12-DESIGN.md` 中的详细设计
2. 参考 RocketMQ 源码
3. 向我请求提示（我会提供 Best 版本的参考实现）

---

## 🚀 开始挑战

准备好了吗？开始实现你的 CommitLog + ConsumeQueue 架构吧！

记住：
- 先理解问题（运行 Buggy 版本测试）
- 再设计方案（参考设计文档）
- 最后实现代码（动手编码）
- 对比性能（验证效果）

Good luck! 🎯
