package one.demo;

import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.spring.autoconfigure.RocketMQProperties;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@RequiredArgsConstructor
@Configuration
public class ProducerConfig {
//    private final RocketMQProperties properties;
//    @Bean
//    public RocketMQTemplate rocketMQTemplate() {
//        RocketMQTemplate rocketMQTemplate = new RocketMQTemplate();
//        rocketMQTemplate.setProducer(defaultMQProducer());
//        return rocketMQTemplate;
//    }
//
//    @Bean
//    public DefaultMQProducer defaultMQProducer() {
//        DefaultMQProducer producer = new DefaultMQProducer(properties.getProducer().getGroup());
//        producer.setNamesrvAddr(properties.getNameServer());
//        return producer;
//    }
}
