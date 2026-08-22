package com.ethanluong.ticketreservation.config;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaTopicConfig {



    @Bean
    public NewTopic topic() {
        return TopicBuilder
                .name("payment.cmd")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic topic2() {
        return TopicBuilder
                .name("payment.evt")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic topic3() {
        return TopicBuilder
                .name("payment.cmd.DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic topic4() {
        return TopicBuilder
                .name("payment.evt.DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }


}
