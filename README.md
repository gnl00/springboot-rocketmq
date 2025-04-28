# springboot-rocketmq

## run
```shell
docker run -d \
  --name rocketmq-namesrv \
  --network=host \
  -e "ROCKETMQ_OPTS=-Drocketmq.namesrv.addr=localhost:9876" \
  dockerpull.cn/apache/rocketmq:5.3.2 \
  sh mqnamesrv

docker run -d \
  --name rocketmq-broker \
  --network=host \
  -e "ROCKETMQ_OPTS=-Drocketmq.namesrv.addr=localhost:9876" \
  dockerpull.cn/apache/rocketmq:5.3.2 \
  sh mqbroker -n localhost:9876
```

## 消息积压

为什么会出现消息积压？

- 生产者消息生产太快；

- 消费者消息生产太慢。

遇到消息积压的情况如何解决？

- 消费者本地运行配置修改，例如消费者客户端的线程数，消费并发度等;

- PullConsumer 配置 `pullBatchSize` 自定义每次拉取消息的大小。需要注意：broker.conf 配置文件默认消息拉取大小为 32，所以默认拉取 32 条消息。如果设置了 pullBatchSize 未生效，则可能是 broker.conf 配置文件大小不够大，需要修改配置文件。
