# Level 9: 死信队列与消息重试（Buggy）

## 场景
- Topic：`level9-order-topic`
- 订单消息在消费失败后被无限重试，最终进入 `%DLQ%level9-order-consumer-buggy`
- 没有任何监控/回查逻辑，死信消息会永久滞留

## Bug 描述
1. **无限重试**：`Level9ConsumerBuggy` 对所有异常都返回 `ConsumeResult.FAILURE`，无论是否为业务异常。
2. **业务异常误重试**：负金额、验证失败等属于用户输入问题，却被当成系统异常处理。
3. **死信无人关注**：消费 16 次失败后进入 Broker DLQ，没有消费者订阅 `%DLQ%...`。
4. **重试退避缺失**：未配置最大重试次数与退避策略，大量失败消息会在短时间内反复推送。

## 快速复现
```bash
# 1. 正常下单
curl "http://localhost:8070/challenge/level9/sendOrder?mode=normal&orderId=L9-OK"

# 2. 业务异常（负金额）
curl "http://localhost:8070/challenge/level9/sendOrder?mode=business_error&amount=-10&orderId=L9-BIZ"

# 3. 系统异常（超时）
curl "http://localhost:8070/challenge/level9/sendOrder?mode=system_timeout&orderId=L9-SYS"

# 4. 查看统计
curl "http://localhost:8070/challenge/level9/checkAll"
```

## 需要你在 Fixed 版本实现的点
- 区分业务异常与系统异常，业务异常应直接 ACK，避免无效重试；
- 配置合理的最大重试次数、退避策略；
- 订阅并监控 `%DLQ%level9-order-consumer-buggy`，提供告警、手动补偿能力；
- 支持将死信重新投递到原 Topic，并在 HTTP 接口中暴露 DLQ 查询/投递功能。
