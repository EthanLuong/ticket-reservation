package com.ethanluong.ticketreservation.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topic ownership rule (R2): each service declares the topic it CONSUMES plus
 * that topic's DLT (which its own error handler writes). reservation-service
 * reads {@code payment.evt}, so it owns these two; payment-service declares
 * the {@code payment.cmd} pair. Partition count lives with the consumer
 * because it is the consumer's parallelism ceiling.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic paymentEvtTopic() {
        return TopicBuilder
                .name("payment.evt")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic paymentEvtDltTopic() {
        return TopicBuilder
                .name("payment.evt.DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }
}
