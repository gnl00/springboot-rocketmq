# Level 8: 消息过滤与标签路由（Buggy）

## 场景简介
- Topic：`level8-order-topic`
- 订单类型：普通、秒杀、预售、VIP
- 过滤维度：Tag、地区(region)、金额(amount)

## Bug 清单
1. **未设置 Tag**：所有消费者都订阅 `*`，收到所有类型的消息。
2. **Tag 拼写不一致**：Producer 使用 `seckill-order`，消费者订阅 `seckill_order`。
3. **SQL 表达式错误**：`region = beijing AND amount > 100` 缺少引号，消费者启动失败。
4. **消费者端过滤**：所有消息都推到客户端再过滤，性能极差。

## 核心接口
```bash
# Tag 路由问题
curl "http://localhost:8070/challenge/level8/sendOrder?type=normal&orderId=ORDER-001"
curl "http://localhost:8070/challenge/level8/sendOrder?type=vip&orderId=ORDER-004"
curl "http://localhost:8070/challenge/level8/checkConsumerStats"

# Tag 拼写问题
curl "http://localhost:8070/challenge/level8/buggy/sendOrder?type=seckill&orderId=ORDER-005"
curl "http://localhost:8070/challenge/level8/checkConsumer?name=strict-seckill-consumer"

# SQL 过滤问题
curl "http://localhost:8070/challenge/level8/sendOrderWithProps?region=beijing&amount=150"
curl "http://localhost:8070/challenge/level8/sendOrderWithProps?region=shanghai&amount=80"
curl "http://localhost:8070/challenge/level8/checkConsumer?name=beijing-consumer"
curl "http://localhost:8070/challenge/level8/checkConsumer?name=high-amount-consumer"

# 性能比较
curl "http://localhost:8070/challenge/level8/batchSend?count=1000"
curl "http://localhost:8070/challenge/level8/compareFilterPerformance"
```

## 修复建议提示
- Producer 必须给不同订单设置合适的 Tag；
- 消费者订阅表达式需要与 Tag 严格一致；
- SQL92 表达式中字符串要加引号，数值比较不要转成字符串；
- 尽量在 Broker 端过滤，减少客户端压力。
