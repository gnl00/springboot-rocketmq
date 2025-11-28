package one.demo;

import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientConfigurationBuilder;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@RequiredArgsConstructor
@Configuration
public class ProducerConfig {
    @Bean
    public Producer producer() throws ClientException {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfigurationBuilder ccBuilder = ClientConfiguration.newBuilder().setEndpoints("localhost:8081");
        ClientConfiguration configuration = ccBuilder.build();
        return provider.newProducerBuilder()
                .setTopics("demo")
                .setClientConfiguration(configuration)
                .build();
    }
}
