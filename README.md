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
