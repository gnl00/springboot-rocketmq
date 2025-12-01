//package one.demo;
//
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Component;
//
//import java.util.concurrent.TimeUnit;
//
//@Component
//@Slf4j
//@RocketMQMessageListener(topic = "demo", consumerGroup = "demo-group", consumeThreadNumber = 64)
//public class MessageHandler implements RocketMQListener<String> {
//    @Override
//    public void onMessage(String message) {
//        try {
//            log.info("receive message: {}", message);
//            TimeUnit.MILLISECONDS.sleep(1200);
//        } catch (InterruptedException e) {
//            log.error("error: {}", e.getMessage());
//        }
//    }
//
//
//}
