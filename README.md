# springboot-rocketmq

## run

```shell
docker pull apache/rocketmq:5.3.2

docker network create rocketmq

# 启动 NameServer
docker run -d --name rmqnamesrv -p 9876:9876 --network rocketmq apache/rocketmq:5.3.2 sh mqnamesrv

# 验证 NameServer 是否启动成功
docker logs -f rmqnamesrv

# 配置 Broker 的IP地址
echo "brokerIP1=127.0.0.1" > broker.conf

# 启动 Broker 和 Proxy
docker run -d \
--name rmqbroker \
--network rocketmq \
-p 10912:10912 -p 10911:10911 -p 10909:10909 \
-p 8080:8080 -p 8081:8081 \
-e "NAMESRV_ADDR=rmqnamesrv:9876" \
-v ./broker.conf:/home/rocketmq/rocketmq-5.3.2/conf/broker.conf \
apache/rocketmq:5.3.2 sh mqbroker --enable-proxy \
-c /home/rocketmq/rocketmq-5.3.2/conf/broker.conf

# 验证 Broker 是否启动成功
docker exec -it rmqbroker bash -c "tail -n 10 /home/rocketmq/logs/rocketmqlogs/proxy.log"
```

## QuickStart

> https://rocketmq.apache.org/zh/docs/quickStart/02quickstartWithDocker

## 消息积压

为什么会出现消息积压？

- 生产者消息生产太快；

- 消费者消息生产太慢。

遇到消息积压的情况如何解决？

- 消费者本地运行配置修改，例如消费者客户端的线程数，消费并发度等;

- PullConsumer 配置 `pullBatchSize` 自定义每次拉取消息的大小。需要注意：broker.conf 配置文件默认消息拉取大小为 32，所以默认拉取 32 条消息。如果设置了 pullBatchSize 未生效，则可能是 broker.conf 配置文件大小不够大，需要修改配置文件。
