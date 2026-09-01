package com.ethanluong.ticketreservation;

import com.ethanluong.ticketreservation.domain.entity.Event;
import com.ethanluong.ticketreservation.domain.entity.Reservation;
import com.ethanluong.ticketreservation.domain.entity.Saga;
import com.ethanluong.ticketreservation.domain.entity.Seat;
import com.ethanluong.ticketreservation.domain.entity.User;
import com.ethanluong.ticketreservation.domain.repository.EventRepository;
import com.ethanluong.ticketreservation.domain.repository.OutboxEntryRepository;
import com.ethanluong.ticketreservation.domain.repository.ProcessedEventRepository;
import com.ethanluong.ticketreservation.domain.repository.ReservationRepository;
import com.ethanluong.ticketreservation.domain.repository.SagaRepository;
import com.ethanluong.ticketreservation.domain.repository.SeatRepository;
import com.ethanluong.ticketreservation.domain.repository.UserRepository;
import com.ethanluong.ticketreservation.domain.type.ReservationStatus;
import com.ethanluong.ticketreservation.domain.type.SagaState;
import com.ethanluong.ticketreservation.domain.type.SeatStatus;
import com.ethanluong.ticketreservation.saga.events.EventEnvelope;
import com.ethanluong.ticketreservation.saga.events.EventTypes;
import com.ethanluong.ticketreservation.saga.events.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Reservation's half of the wire seam, post-split (R2): envelopes shaped like the
 * ones payment-service produces are published straight onto {@code payment.evt}
 * and must drive PaymentEventListener → SagaOrchestrator.handlePaymentEvent to
 * the right terminal states. Heir to the orchestrator-side guarantees of the
 * deleted SagaE2EIT — its full in-one-JVM loop now spans deployables (the compose
 * smoke owns that journey), but dedup, the wrong-state guard, the refund close,
 * and the poison-pill DLT are THIS service's own guarantees and stay tested here.
 *
 * Harness carries SagaE2EIT's decisions forward:
 * - app.scheduling.enabled=true — live publisher/sweeper wiring stays part of a
 *   real boot; fixtures are FRESH so the 30s sweeper cannot race them.
 * - Awaitility polls the user-visible outcome first, internals after.
 * - Same-key partition ordering makes sentinel markers prove consumption.
 */
@SpringBootTest(properties = "app.scheduling.enabled=true")
@Import({TestcontainersConfiguration.class, PaymentEvtConsumptionIT.EvtDltProbeConfig.class})
class PaymentEvtConsumptionIT {

    private static final Duration LOOP_SETTLE = Duration.ofSeconds(20);

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private BlockingQueue<ConsumerRecord<String, String>> evtDlt;

    @Autowired private SagaRepository sagaRepository;
    @Autowired private OutboxEntryRepository outboxEntryRepository;
    @Autowired private ProcessedEventRepository processedEventRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private UserRepository userRepository;

    @TestConfiguration(proxyBeanMethods = false)
    static class EvtDltProbeConfig {

        private final BlockingQueue<ConsumerRecord<String, String>> queue = new LinkedBlockingQueue<>();

        @Bean
        BlockingQueue<ConsumerRecord<String, String>> evtDltRecords() {
            return queue;
        }

        @KafkaListener(topics = "payment.evt.DLT", groupId = "evt-dlt-probe",
                properties = "auto.offset.reset:earliest")
        void onDltRecord(ConsumerRecord<String, String> record, Acknowledgment ack) {
            queue.add(record);
            ack.acknowledge();
        }
    }

    @AfterEach
    void cleanup() {
        sagaRepository.deleteAllInBatch();
        outboxEntryRepository.deleteAllInBatch();
        processedEventRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("PaymentConfirmed from the wire: AWAITING_PAYMENT saga → COMPLETED, reservation CONFIRMED")
    void paymentConfirmed_completesSaga() {
        Saga saga = seedSagaDirect(SagaState.AWAITING_PAYMENT, ReservationStatus.HELD);

        sendPaymentEvent(saga.getId(), EventTypes.PAYMENT_CONFIRMED);

        await().atMost(LOOP_SETTLE).untilAsserted(() ->
                assertThat(reservationRepository.findById(saga.getReservationId()).orElseThrow().getStatus())
                        .isEqualTo(ReservationStatus.CONFIRMED));
        assertThat(sagaRepository.findById(saga.getId()).orElseThrow().getState())
                .isEqualTo(SagaState.COMPLETED);
    }

    @Test
    @DisplayName("PaymentFailed from the wire: AWAITING_PAYMENT saga → CANCELLED, reservation CANCELLED")
    void paymentFailed_cancels() {
        Saga saga = seedSagaDirect(SagaState.AWAITING_PAYMENT, ReservationStatus.HELD);

        sendPaymentEvent(saga.getId(), EventTypes.PAYMENT_FAILED);

        await().atMost(LOOP_SETTLE).untilAsserted(() ->
                assertThat(reservationRepository.findById(saga.getReservationId()).orElseThrow().getStatus())
                        .isEqualTo(ReservationStatus.CANCELLED));
        assertThat(sagaRepository.findById(saga.getId()).orElseThrow().getState())
                .isEqualTo(SagaState.CANCELLED);
    }

    @Test
    @DisplayName("RefundConfirmed from the wire closes a COMPENSATING saga → CANCELLED")
    void refundConfirmed_closesCompensation() {
        Saga saga = seedSagaDirect(SagaState.COMPENSATING, ReservationStatus.CANCELLED);

        sendPaymentEvent(saga.getId(), EventTypes.REFUND_CONFIRMED);

        await().atMost(LOOP_SETTLE).untilAsserted(() ->
                assertThat(sagaRepository.findById(saga.getId()).orElseThrow().getState())
                        .isEqualTo(SagaState.CANCELLED));
    }

    @Test
    @DisplayName("duplicate PaymentConfirmed: redelivered eventId no-ops via dedup, saga stays COMPLETED")
    void duplicatePaymentConfirmed_noOps() throws Exception {
        Saga saga = seedSagaDirect(SagaState.AWAITING_PAYMENT, ReservationStatus.HELD);
        String original = envelopeJson(UUID.randomUUID(), saga.getId(), EventTypes.PAYMENT_CONFIRMED);
        kafkaTemplate.send(KafkaTopics.PAYMENT_EVT, saga.getId().toString(), original)
                .get(10, TimeUnit.SECONDS);
        await().atMost(LOOP_SETTLE).untilAsserted(() ->
                assertThat(sagaRepository.findById(saga.getId()).orElseThrow().getState())
                        .isEqualTo(SagaState.COMPLETED));

        // Redeliver the SAME envelope (same eventId ⇒ the dedup path, not the guard).
        kafkaTemplate.send(KafkaTopics.PAYMENT_EVT, saga.getId().toString(), original)
                .get(10, TimeUnit.SECONDS);

        // Same key ⇒ same partition ⇒ the sentinel is consumed strictly AFTER the
        // duplicate; its marker appearing proves the duplicate was consumed and skipped.
        UUID sentinelId = sendPaymentEvent(saga.getId(), EventTypes.PAYMENT_CONFIRMED);
        await().atMost(LOOP_SETTLE).untilAsserted(() ->
                assertThat(processedEventRepository.existsById(sentinelId)).isTrue());

        assertThat(sagaRepository.findById(saga.getId()).orElseThrow().getState())
                .isEqualTo(SagaState.COMPLETED);
        assertThat(reservationRepository.findById(saga.getReservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("out-of-order PaymentConfirmed: wrong-state guard skips it, marker makes the skip permanent")
    void outOfOrderEvent_skippedByWrongStateGuard() {
        // COMPENSATING saga: PaymentConfirmed is only legal from AWAITING_PAYMENT (ADR 0003:
        // log + skip, never park). Marker saved BEFORE the state check ⇒ permanent skip.
        Saga saga = seedSagaDirect(SagaState.COMPENSATING, ReservationStatus.CANCELLED);

        UUID eventId = sendPaymentEvent(saga.getId(), EventTypes.PAYMENT_CONFIRMED);

        await().atMost(LOOP_SETTLE).untilAsserted(() ->
                assertThat(processedEventRepository.existsById(eventId)).isTrue());
        assertThat(sagaRepository.findById(saga.getId()).orElseThrow().getState())
                .isEqualTo(SagaState.COMPENSATING);
        assertThat(reservationRepository.findById(saga.getReservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    @DisplayName("poison pill on payment.evt is dead-lettered with diagnostic headers, and the consumer keeps going")
    void poisonPill_isDeadLettered() throws Exception {
        // Reservation's own error handler (this service's copy of the DLT config) — the
        // payment.cmd twin of this proof moved to payment-service with its listener.
        kafkaTemplate.send(KafkaTopics.PAYMENT_EVT, "poison-key-1", "{this is not json")
                .get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, String> first = evtDlt.poll(30, TimeUnit.SECONDS);
        assertThat(first).as("poison pill should reach payment.evt.DLT").isNotNull();
        assertThat(header(first, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(KafkaTopics.PAYMENT_EVT);
        assertThat(header(first, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).contains("tools.jackson");

        kafkaTemplate.send(KafkaTopics.PAYMENT_EVT, "poison-key-2", "also-not-json}}")
                .get(10, TimeUnit.SECONDS);
        ConsumerRecord<String, String> second = evtDlt.poll(30, TimeUnit.SECONDS);
        assertThat(second).as("consumer should progress past poison and DLT the next one too").isNotNull();
        assertThat(second.value()).isEqualTo("also-not-json}}");
    }

    // -------------------------------------------------------------- helpers

    /** Direct saga seed (SagaTimeoutIT pattern) — fresh created_at so the live sweeper can't race it. */
    private Saga seedSagaDirect(SagaState sagaState, ReservationStatus reservationStatus) {
        User user = userRepository.save(User.builder()
                .email("evt-" + UUID.randomUUID() + "@test.local")
                .passwordHash(passwordEncoder.encode("password12345"))
                .displayName("evt-tester")
                .build());
        Event event = eventRepository.save(Event.builder()
                .name("Evt Consumption Test Event")
                .venue("Test Arena")
                .startsAt(OffsetDateTime.now().plusDays(30))
                .endsAt(OffsetDateTime.now().plusDays(30).plusHours(2))
                .build());
        Seat seat = seatRepository.save(Seat.builder()
                .event(event)
                .seatLabel("E-1")
                .priceCents(5_000L)
                .status(SeatStatus.HELD)
                .version(0L)
                .build());
        Reservation reservation = reservationRepository.save(Reservation.builder()
                .id(UUID.randomUUID())
                .user(user)
                .seat(seat)
                .status(reservationStatus)
                .expiresAt(OffsetDateTime.now().plusMinutes(10))
                .build());
        return sagaRepository.save(Saga.builder()
                .id(UUID.randomUUID())
                .type("BOOKING")
                .reservationId(reservation.getId())
                .state(sagaState)
                .build());
    }

    /** Publishes a payment-service-shaped envelope onto payment.evt; returns its eventId. */
    private UUID sendPaymentEvent(UUID sagaId, String eventType) {
        UUID eventId = UUID.randomUUID();
        try {
            kafkaTemplate.send(KafkaTopics.PAYMENT_EVT, sagaId.toString(),
                            envelopeJson(eventId, sagaId, eventType))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("test send to payment.evt failed", e);
        }
        return eventId;
    }

    private String envelopeJson(UUID eventId, UUID sagaId, String eventType) {
        EventEnvelope envelope = new EventEnvelope(
                eventId, eventType, 1, OffsetDateTime.now(), sagaId,
                objectMapper.createObjectNode());
        return objectMapper.writeValueAsString(envelope);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
