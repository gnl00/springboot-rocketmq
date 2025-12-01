package one.demo;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.core.RocketMQClientTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@SpringBootApplication
public class ConsumerMain {
    public static void main(String[] args) {
        SpringApplication.run(ConsumerMain.class, args);
    }

//    @Autowired
//    private RocketMQClientTemplate template;
//
//    private static int consumeCount = 0;
//
//    private static final Executor EXECUTOR = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() << 1);
//
//    @GetMapping("/consume")
//    public void consume() {
//        log.info("consume start");
//        new Thread(() -> {
//            while (true) {
//                try {
//                    List<MessageView> messages = template.receive(64, Duration.ofSeconds(30));
//                    messages.forEach(messageView -> {
//                        EXECUTOR.execute(() -> process(consumeCount++, messageView));
//                    });
//                } catch (ClientException e) {
//                    log.error("consume error", e);
//                }
//            }
//        }).start();
//    }
//
//    private void process(int count, MessageView messageView) {
//        try {
//            log.info("consume message-{}: {}", count, StandardCharsets.UTF_8.decode(messageView.getBody()));
//            template.ack(messageView);
//            log.info("Message is acknowledged successfully, messageId={}", messageView.getMessageId());
//            TimeUnit.MILLISECONDS.sleep(500);
//        } catch (InterruptedException e) {
//            log.error("consume error", e);
//        } catch (Throwable t) {
//            log.error("Message is failed to be acknowledged, messageId={}", messageView.getMessageId(), t);
//        }
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

}
