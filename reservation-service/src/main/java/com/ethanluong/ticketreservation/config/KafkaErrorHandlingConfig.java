package com.ethanluong.ticketreservation.config;

import tools.jackson.core.JacksonException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    DefaultErrorHandler defaultErrorHandler(KafkaTemplate<?, ?> kafkaTemplate) {
        // Spring Kafka 4.x's default DLT suffix is "-dlt"; our declared topics use ".DLT"
        // (KafkaTopicConfig, partitions matched 3=3) — resolve explicitly so the two agree.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(record.topic() + ".DLT", record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2));

        // tools.jackson (Jackson 3) — the listener's manual readValue throws this; fasterxml's
        // JacksonException is a different hierarchy and would never match here.
        handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class);
        return handler;
    }
}

