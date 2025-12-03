package one.demo.challenge.level5;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.stereotype.Component;

@Slf4j
// @Component
public class OrderedConsumer {

    @PostConstruct
    public void init() throws ClientException {
        ClientServiceProvider provider = ClientServiceProvider.loadService();

        PushConsumer pushConsumer = provider.newPushConsumerBuilder()
                .setConsumerGroup("")
                // .setClientConfiguration(clientConfig) // 你的 ClientConfiguration
                .setMessageListener(new MessageListener() {
                    @Override
                    public ConsumeResult consume(MessageView messageView) {
                        return ConsumeResult.SUCCESS;
                    }
                })
                .build();

        pushConsumer.subscribe("order-status-topic", FilterExpression.SUB_ALL);
    }

}
