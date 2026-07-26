package com.ethanluong.ticketreservation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Canary for the Phase 2 Kafka infrastructure: proves broker container,
 * @ServiceConnection wiring, topic creation, serialization, and MANUAL
 * ack-mode all work end-to-end BEFORE any saga logic exists. If a saga IT
 * misbehaves later, a green canary means the infra is not the variable.
 *
 * STUBS — wiring is done; every TODO(you) is a line for you to write.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, KafkaCanaryIT.CanaryListenerConfig.class})
class KafkaCanaryIT {

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private BlockingQueue<String> received;

    @TestConfiguration(proxyBeanMethods = false)
    static class CanaryListenerConfig {

        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();

        @Bean
        BlockingQueue<String> received() {
            return queue;
        }

        @KafkaListener(topics = "payment.cmd", groupId = "canary",
                properties = "auto.offset.reset:earliest")
        void onMessage(String value, Acknowledgment ack) {
            // TODO(you): hand the value to the queue, then acknowledge.
            //  Order matters — think about what happens if the JVM died
            //  between the two lines, and which order makes the test
            //  at-least-once rather than at-most-once. (Same reasoning
            //  you'll use for processed_events-then-ack in card 6.)
            queue.add(value);
            ack.acknowledge();
        }
    }

    @Test
    @DisplayName("canary: message sent to payment.cmd round-trips through the broker")
    void sendAndReceive() throws Exception {
        // TODO(you): send ("payment.cmd", "canary-key", "ping") via kafkaTemplate.
        //  send() returns a CompletableFuture — decide whether the test should
        //  proceed before the broker has acked the send, and make the code say so.
        kafkaTemplate.send("payment.cmd", "canary-key", "ping").get(10, TimeUnit.SECONDS);

        // TODO(you): take from `received` with a bounded wait (poll + timeout,
        //  NOT take()) and assert the payload is exactly "ping".
        //  Why bounded: a broken consumer should fail this test in seconds,
        //  not hang the suite forever.
        String payload = received.poll(10, TimeUnit.SECONDS);
        assertThat(payload).isEqualTo("ping");

    }
}
