//package one.demo;
//
//import jakarta.annotation.PostConstruct;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.rocketmq.client.consumer.DefaultLitePullConsumer;
//import org.apache.rocketmq.client.exception.MQClientException;
//import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
//import org.springframework.boot.SpringApplication;
//import org.springframework.boot.autoconfigure.SpringBootApplication;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.RestController;
//
//import java.nio.charset.Charset;
//import java.util.concurrent.Executor;
//import java.util.concurrent.Executors;
//import java.util.concurrent.TimeUnit;
//
//@Slf4j
//@RestController
//@SpringBootApplication
//public class ConsumerV4Main {
//    public static void main(String[] args) {
//        SpringApplication.run(ConsumerV4Main.class, args);
//    }
//
//    private static int consumeCount = 0;
//    private DefaultLitePullConsumer consumer;
//
//    @PostConstruct
//    public void init() {
//        consumer = new DefaultLitePullConsumer("demo-group");
//        consumer.setAutoCommit(false);
//        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
//        // broker.conf 配置文件默认大小为 32，所以默认拉取 32 条消息。如果设置了 pullBatchSize 未生效，则可能是 broker.conf 配置文件大小不够大，需要修改配置文件
//        consumer.setPullBatchSize(100);
//        consumer.setNamesrvAddr("localhost:9876");
//        try {
//            consumer.subscribe("demo");
//            consumer.start();
//        } catch (MQClientException e) {
//            log.error("consumer init error", e);
//        }
//    }
//
//    private static final Executor EXECUTOR = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() << 1);
//
//    @GetMapping("/consume")
//    public void consume() {
//        consumer.poll().forEach(messageExt -> {
//            EXECUTOR.execute(() -> process(consumeCount++, new String(messageExt.getBody(), Charset.defaultCharset())));
//            // consumer.commit();
//        });
//    }
//
//    private void process(int count, String msg) {
//        try {
//            log.info("consume message-{}: {}", count, msg);
//            TimeUnit.MILLISECONDS.sleep(500);
//        } catch (InterruptedException e) {
//            log.error("consume error", e);
//        }
//    }
//
//}
