package com.ethanluong.payment;

import com.ethanluong.payment.entity.Payment;
import com.ethanluong.payment.events.ChargeCard;
import com.ethanluong.payment.events.EventEnvelope;
import com.ethanluong.payment.events.EventTypes;
import com.ethanluong.payment.events.KafkaTopics;
import com.ethanluong.payment.repository.PaymentRepository;
import com.ethanluong.payment.type.PaymentStatus;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The extraction's birth certificate (R2 Task 6): boots THIS deployable against
 * its own Postgres (Flyway V1 applies) + Kafka, feeds it a ChargeCard on
 * payment.cmd, and asserts the outcome event appears on payment.evt and the
 * payments row exists. Proves the service works end-to-end BEFORE the Task 8
 * cutover deletes reservation's in-app copy.
 *
 * Scheduling stays ENABLED here — the outbox relay's background drain is part
 * of the path under test (command → dedup+write+outbox tx → relay → payment.evt),
 * unlike the unit tests that call publish() directly.
 */
@SpringBootTest(properties = {
        // @ServiceConnection supplies the real datasource; this only keeps the
        // ${PAYMENT_DB_PASSWORD} placeholder from failing property binding.
        "spring.datasource.password=overridden-by-service-connection",
})
@Import({TestcontainersConfiguration.class, PaymentRoundTripIT.EvtListenerConfig.class})
class PaymentRoundTripIT {

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private BlockingQueue<String> receivedEvts;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private Clock clock;

    @TestConfiguration(proxyBeanMethods = false)
    static class EvtListenerConfig {

        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();

        @Bean
        BlockingQueue<String> receivedEvts() {
            return queue;
        }

        // The service only declares what it consumes (payment.cmd — see
        // KafkaTopicConfig's ownership rule); the test consumes payment.evt,
        // so the test declares it. Same rule, one level down.
        @Bean
        NewTopic paymentEvtTopic() {
            return TopicBuilder.name(KafkaTopics.PAYMENT_EVT).partitions(3).replicas(1).build();
        }

        @KafkaListener(topics = KafkaTopics.PAYMENT_EVT, groupId = "birth-cert",
                properties = "auto.offset.reset:earliest")
        void onEvt(String value, Acknowledgment ack) {
            queue.add(value);
            ack.acknowledge();
        }
    }

    private void sendChargeCard(UUID sagaId, long amountCents) throws Exception {
        EventEnvelope cmd = new EventEnvelope(UUID.randomUUID(), EventTypes.CHARGE_CARD, 1,
                OffsetDateTime.now(clock), sagaId, objectMapper.valueToTree(new ChargeCard(amountCents)));
        kafkaTemplate.send(KafkaTopics.PAYMENT_CMD, sagaId.toString(), objectMapper.writeValueAsString(cmd))
                .get(10, TimeUnit.SECONDS);
    }

    /** Bounded wait for the evt belonging to OUR saga — other tests' events may interleave. */
    private EventEnvelope awaitEvtFor(UUID sagaId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            String raw = receivedEvts.poll(1, TimeUnit.SECONDS);
            if (raw == null) continue;
            EventEnvelope evt = objectMapper.readValue(raw, EventEnvelope.class);
            if (sagaId.equals(evt.sagaId())) return evt;
        }
        throw new AssertionError("no payment.evt for saga " + sagaId + " within 30s");
    }

    @Test
    @DisplayName("ChargeCard under the limit → PaymentConfirmed on payment.evt + AUTHORIZED row")
    void chargeCard_approved_roundTrip() throws Exception {
        UUID sagaId = UUID.randomUUID();

        sendChargeCard(sagaId, 5_000);

        assertThat(awaitEvtFor(sagaId).eventType()).isEqualTo(EventTypes.PAYMENT_CONFIRMED);
        Optional<Payment> payment = paymentRepository.findBySagaId(sagaId);
        assertThat(payment).isPresent();
        assertThat(payment.get().getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.get().getGatewayRef()).isEqualTo("mock-" + sagaId);
    }

    @Test
    @DisplayName("ChargeCard over the limit → PaymentFailed on payment.evt + FAILED row")
    void chargeCard_declined_roundTrip() throws Exception {
        UUID sagaId = UUID.randomUUID();

        sendChargeCard(sagaId, 15_000);

        assertThat(awaitEvtFor(sagaId).eventType()).isEqualTo(EventTypes.PAYMENT_FAILED);
        Optional<Payment> payment = paymentRepository.findBySagaId(sagaId);
        assertThat(payment).isPresent();
        assertThat(payment.get().getStatus()).isEqualTo(PaymentStatus.FAILED);
    }
}
