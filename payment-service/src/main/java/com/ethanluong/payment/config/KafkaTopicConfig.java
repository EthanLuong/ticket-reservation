package com.ethanluong.payment.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topic ownership rule (R2): each service declares the topic it CONSUMES plus
 * that topic's DLT (which its own error handler writes). payment-service reads
 * {@code payment.cmd}, so it owns these two; reservation-service reads
 * {@code payment.evt} and keeps declaring that pair. Partition count lives with
 * the consumer because it is the consumer's parallelism ceiling. Creation is
 * idempotent, so a redeclare elsewhere would be harmless — just sloppy.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic paymentCmdTopic() {
        return TopicBuilder
                .name("payment.cmd")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic paymentCmdDltTopic() {
        return TopicBuilder
                .name("payment.cmd.DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }
}
