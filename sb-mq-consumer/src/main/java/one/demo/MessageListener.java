package one.demo;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RocketMQMessageListener(topic = "demo", tag = "*", consumerGroup = "demo-group", endpoints = "localhost:8080", namespace = "ns-demo")
public class MessageListener implements RocketMQListener {

    @PostConstruct
    private void init() {
        Class<MessageListener> clazz = MessageListener.class;
        RocketMQMessageListener annotation = clazz.getAnnotation(RocketMQMessageListener.class);
        log.info("consumer {}:{}:{} started", annotation.consumerGroup(), annotation.topic(), annotation.tag());
    }

    private static final AtomicLong counter = new AtomicLong();

    @Override
    public ConsumeResult consume(MessageView messageView) {
        return process(counter.addAndGet(1L), messageView);
    }

    private ConsumeResult process(long count, MessageView messageView) {
        try {
            TimeUnit.MILLISECONDS.sleep(300);
            log.info("consume message-{}: {}", count, StandardCharsets.UTF_8.decode(messageView.getBody()));
            log.info("Message is acknowledged successfully, messageId={}", messageView.getMessageId());
            return ConsumeResult.SUCCESS;
        } catch (InterruptedException e) {
            log.error("consume error", e);
        } catch (Throwable t) {
            log.error("Message is failed to be acknowledged, messageId={}", messageView.getMessageId(), t);
        }
        return ConsumeResult.FAILURE;
    }
}
