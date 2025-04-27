package one.demo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageProducer {

    private final RocketMQTemplate rocketMQTemplate;

    public void sendMessage(String topic, String message) {
        rocketMQTemplate.convertAndSend(topic, message);
        log.info("send to topic: {} message: {}", topic, message);
    }

}
