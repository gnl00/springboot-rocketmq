# Level 2 问题清单与提示

## 🐛 代码中埋入的 11 个 Bug

### Bug 1: 超时时间设置过长
**位置：** `Level2ProducerBuggy.java:48`
```java
.setRequestTimeout(Duration.ofSeconds(30))  // 30秒太长了！
```
**问题：** 如果 Broker 无响应，每次请求会阻塞 30 秒
**影响：** 用户体验差，接口超时

---

### Bug 2: 重试次数过多
**位置：** `Level2ProducerBuggy.java:53`
```java
.setMaxAttempts(10)  // 重试 10 次！
```
**问题：** 最坏情况：30秒 × 10次 = 5分钟
**影响：** 严重的接口阻塞

---

### Bug 3: 同步发送阻塞主线程
**位置：** `Level2ProducerBuggy.java:81`
```java
SendReceipt receipt = producer.send(msg);  // 同步发送，阻塞等待
```
**问题：** 发送失败时会一直阻塞，无法处理其他请求
**影响：** 高并发时，所有请求都会排队等待

---

### Bug 4: 发送失败后消息丢失
**位置：** `Level2ProducerBuggy.java:88`
```java
catch (ClientException e) {
    log.error("消息发送失败...");
    return "发送失败...";  // 消息就这样丢了！
}
```
**问题：** 没有任何兜底措施，消息彻底丢失
**影响：** 数据丢失，业务受损

---

### Bug 5-9: 自定义重试逻辑的问题
**位置：** `sendWithRetry` 方法

**Bug 5:** 在 RocketMQ 已有重试机制的基础上又加了一层重试（双重重试！）

**Bug 6:** 没有重试间隔
```java
while (retryCount < maxRetries) {
    try { ... } catch {
        retryCount++;
        // 立即重试，没有等待！
    }
}
```
**影响：** CPU 飙升，加剧 Broker 压力

**Bug 7:** 没有指数退避策略
- 应该：第1次等待100ms，第2次等待200ms，第3次等待400ms...
- 实际：立即重试

**Bug 8:** 同步阻塞
- 用户在等待重试完成才能得到响应

**Bug 9:** 重试失败后仍然丢失消息

---

### Bug 10: 串行发送导致级联阻塞
**位置：** `batchSend` 方法
```java
for (int i = 0; i < count; i++) {
    producer.send(message);  // 串行发送
}
```
**问题：** 如果第1条消息失败重试，后面99条都要等待
**影响：** 批量发送性能极差

---

### Bug 11: 失败消息未记录
**位置：** `batchSend` 方法
```java
catch (ClientException e) {
    failCount++;
    log.error("失败");  // 只记录了日志！
}
```
**问题：** 无法知道哪些消息失败了，无法补偿
**影响：** 失败消息无法追溯和重发

---

## 🎯 挑战目标

### 1. 优化超时和重试配置
```java
// 建议配置
.setRequestTimeout(Duration.ofSeconds(3))  // 3-5秒即可
.setMaxAttempts(3)  // 2-3次重试足够
```

### 2. 实现异步发送
```java
// 使用异步发送，不阻塞主线程
producer.sendAsync(msg, new SendCallback() {
    @Override
    public void onSuccess(SendReceipt sendReceipt) {
        // 成功处理
    }

    @Override
    public void onException(Throwable throwable) {
        // 失败处理：记录到数据库或重试队列
    }
});
```

### 3. 实现失败消息兜底
可选方案：
- 写入本地文件
- 持久化到数据库
- 发送到死信队列
- 写入 Redis 队列

### 4. 添加重试间隔和指数退避
```java
// 示例：指数退避
int retryCount = 0;
int baseDelay = 100;  // 基础延迟 100ms
while (retryCount < maxRetries) {
    try {
        producer.send(msg);
        break;  // 成功
    } catch (Exception e) {
        retryCount++;
        int delay = baseDelay * (1 << retryCount);  // 100, 200, 400, 800...
        Thread.sleep(delay);
    }
}
```

### 5. 批量发送优化
- 使用异步发送
- 使用 CompletableFuture 并行发送
- 失败消息收集到列表中，后续补偿

---

## 🔍 测试验证

### 测试1: 验证超时时间
```bash
# 停止 Broker
docker stop rmqbroker

# 测试发送（观察响应时间）
time curl "http://localhost:8070/challenge/level2/send?message=test"

# 应该在 3-5 秒内返回，而不是 30 秒
```

### 测试2: 验证异步发送
```bash
# 异步发送应该立即返回（几十毫秒内）
time curl "http://localhost:8070/challenge/level2/sendAsync?message=test"
```

### 测试3: 验证失败消息记录
```bash
# 发送失败后，检查数据库/文件是否有记录
curl "http://localhost:8070/challenge/level2/send?message=test"
# 查询失败消息表
curl "http://localhost:8070/challenge/level2/failedMessages"
```

---

## 💡 关键知识点

1. **同步 vs 异步发送**
   - 同步：阻塞等待，可靠性高，性能低
   - 异步：立即返回，性能高，需要回调处理结果

2. **重试策略**
   - 固定间隔：每次等待相同时间
   - 指数退避：等待时间指数增长（推荐）
   - 最大重试次数：避免无限重试

3. **超时时间设置**
   - 太短：容易误判为失败
   - 太长：用户等待时间长
   - 建议：3-5秒

4. **失败兜底方案**
   - 持久化失败消息
   - 定时任务重试
   - 人工补偿机制

开始你的挑战吧！💪