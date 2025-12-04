# 顺序消息

> https://rocketmq.apache.org/zh/docs/featureBehavior/03fifomessage

## 主题

首先，确保创建的主题 MessageType=FIFO。Apache RocketMQ 5.0版本下创建主题操作，推荐使用mqadmin工具，需要注意的是，对于消息类型需要通过属性参数添加。示例如下：

```shell
sh mqadmin updateTopic -t <topic_name> -c <cluster_name> -a +message.type=FIFO
sh mqadmin updateTopic -n <nameserver_address> -t <topic_name> -c <cluster_name> -a +message.type=FIFO
```

对应参数如下：

```shell
-c 集群名称
-t Topic名称
-n Nameserver地址
-o 是否是 order 消息 true|false
```

## 消费者组

需要注意，对于订阅消费组顺序类型需要通过 -o 选项设置。示例如下：

```shell
sh mqadmin updateSubGroup -c <cluster_name> -g <consumer_group_name> -o true
sh mqadmin updateSubGroup -c <cluster_name> -g <consumer_group_name> -n <nameserver_address> -o true
```

## 消息生产者

和普通消息发送相比，顺序消息发送必须要设置消息组。消息组的粒度建议按照业务场景，尽可能细粒度设计，以便实现业务拆分和并发扩展。

比如说订单消息，可以将 `messageGroup` 设置成 `orderId`。以 `rocketmq-client-java` 的 `5.0.7` 版本为例：

```java
// 使用 MessageGroup 实现 FIFO 顺序
// 关键：每个订单使用独立的 MessageGroup（按 orderId 分区）
// 效果：同一订单的消息严格 FIFO，不同订单可以并发处理
Message message = provider.newMessageBuilder()
        .setTopic(TOPIC)
        .setTag("status-change")
        .setKeys(orderId)
        .setMessageGroup(orderId)  // 每个订单独立的 MessageGroup，保证订单内 FIFO，订单间并发
        .setBody(messageBody.getBytes(StandardCharsets.UTF_8))
        .build();

SendReceipt receipt = producer.send(message);
```

## 消息消费者

如果使用 `@RocketMQMessageListener` 需要注意配置正确的 FIFO 消费者组 `consumerGroup`。
只有正确的设置了 `consumerGroup` 参数，才可以在多个消费者线程并行消费的情况下保持正确的消息消费的顺序。

```java
@Component
@RocketMQMessageListener(
        topic = "order-status-topic",
        tag = "*",
        consumerGroup = "fifoGroup",
        endpoints = "localhost:8080"
)
public class Level5ConsumerFixed implements RocketMQListener {}
```

如果使用自定义的消费者：

```java
private final ObjectMapper objectMapper = new ObjectMapper();
private final PushConsumer pushConsumer;
public OrderedPushConsumer(final OrderStatusManager orderStatusManager) throws ClientException {
    ClientServiceProvider provider = ClientServiceProvider.loadService();
    ClientConfiguration clientConfig = ClientConfiguration.newBuilder()
            .setEndpoints("localhost:8080")
            .build();
    pushConsumer = provider.newPushConsumerBuilder()
            .setConsumerGroup("fifoGroup")
            .setSubscriptionExpressions(Map.of("order-status-topic", FilterExpression.SUB_ALL))
            .setClientConfiguration(clientConfig)
            .setMessageListener(messageView -> {
                try {
                    String messageBody = StandardCharsets.UTF_8.decode(messageView.getBody()).toString();
                    OrderStatusEvent event = objectMapper.readValue(messageBody, OrderStatusEvent.class);

                    log.info("📥 收到订单状态变更消息 - {}, Thread: {}",
                            event, Thread.currentThread().getName());

                    // 模拟处理耗时，加剧乱序问题
                    TimeUnit.MILLISECONDS.sleep(50 + (int) (Math.random() * 100));

                    // 更新订单状态
                    boolean success = orderStatusManager.updateStatus(
                            event.getOrderId(),
                            event.getStatus(),
                            event.getSequenceNo()
                    );

                    if (!success) {
                        log.warn("⚠️ 订单状态更新失败 - {}", event);
                    }

                    return ConsumeResult.SUCCESS;
                } catch (Exception e) {
                    log.error("消费消息失败 - MessageId: {}", messageView.getMessageId(), e);
                    return ConsumeResult.FAILURE;
                }
            })
            .build();
    log.info("OrderedConsumer 初始化完成");
}
```