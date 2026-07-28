package com.ethanluong.ticketreservation;

import com.ethanluong.ticketreservation.domain.entity.Event;
import com.ethanluong.ticketreservation.domain.entity.OutboxEntry;
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
import com.ethanluong.ticketreservation.payment.PaymentProcessedEventRepository;
import com.ethanluong.ticketreservation.payment.PaymentRepository;
import com.ethanluong.ticketreservation.payment.PaymentStatus;
import com.ethanluong.ticketreservation.saga.events.EventEnvelope;
import com.ethanluong.ticketreservation.saga.events.EventTypes;
import com.ethanluong.ticketreservation.saga.events.KafkaTopics;
import com.ethanluong.ticketreservation.saga.events.PaymentConfirmed;
import com.ethanluong.ticketreservation.service.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end saga ITs: the FULL loop against real Postgres + Kafka + Redis —
 * reserve() → saga row + outbox ChargeCard → publisher → payment component →
 * outbox reply → publisher → orchestrator → terminal state.
 *
 * Harness decisions (argued out on the board card, 2026-07-28):
 * - app.scheduling.enabled=true — the OutboxPublisher and SagaTimeoutSweeper are
 *   part of the system under test here, not background noise. The property set
 *   differs from the scheduling-off ITs, so Spring builds a SEPARATE context
 *   (own containers) — this class cannot contaminate them.
 * - Live schedulers mean fixtures must be FRESH (a backdated AWAITING_PAYMENT
 *   saga gets swept mid-test) — except the timeout test, where the backdate IS
 *   the fixture. No FixedClockConfig: the sweeper must age rows on the wall clock.
 * - Awaitility polls the USER-VISIBLE outcome (reservation status) first; internal
 *   assertions (saga state, payment rows, outbox marks) come after the loop settles.
 * - Async consumption is proven by partition ordering: messages with the same key
 *   land on the same partition and are consumed in order, so a sentinel event's
 *   dedup marker appearing proves everything sent before it was consumed too.
 */
@SpringBootTest(properties = "app.scheduling.enabled=true")
@Import(TestcontainersConfiguration.class)
class SagaE2EIT {

    private static final Duration LOOP_SETTLE = Duration.ofSeconds(20);

    @Autowired private ReservationService reservationService;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;

    @Autowired private SagaRepository sagaRepository;
    @Autowired private OutboxEntryRepository outboxEntryRepository;
    @Autowired private ProcessedEventRepository processedEventRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentProcessedEventRepository paymentProcessedEventRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private UserRepository userRepository;

    @AfterEach
    void cleanup() {
        // FK order: sagas reference reservations — children first (mirror of insert order)
        sagaRepository.deleteAllInBatch();
        outboxEntryRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        paymentProcessedEventRepository.deleteAllInBatch();
        processedEventRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    // ------------------------------------------------------- mandatory three

    @Test
    @DisplayName("happy path: reserve → saga COMPLETED, reservation CONFIRMED, one AUTHORIZED payment, outbox drained")
    void happyPath_reserveToConfirmed() {
        Fixture fx = seedUserEventSeat(5_000L); // ≤ $100 — gateway approves

        Reservation reservation = reservationService.reserve(fx.userId(), fx.seatId());

        await().atMost(LOOP_SETTLE).untilAsserted(() ->
                assertThat(reservationRepository.findById(reservation.getId()).orElseThrow().getStatus())
                        .isEqualTo(ReservationStatus.CONFIRMED));

        Saga saga = sagaFor(reservation.getId());
        assertThat(saga.getState()).isEqualTo(SagaState.COMPLETED);
        assertThat(paymentRepository.findBySagaId(saga.getId())).hasValueSatisfying(payment -> {
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(payment.getGatewayRef()).isEqualTo("mock-" + saga.getId());
        });
        // Both hops (ChargeCard + PaymentConfirmed) went through the outbox and got marked
        await().atMost(LOOP_SETTLE).untilAsserted(() ->
                assertThat(outboxEntryRepository.findAll())
                        .allSatisfy(row -> assertThat(row.getProcessedAt()).isNotNull()));
    }

    @Test
    @DisplayName("payment declines: reserve over $100 → saga CANCELLED, reservation CANCELLED, one FAILED payment")
    void paymentFails_reservationCancelled() {
        Fixture fx = seedUserEventSeat(15_000L); // > $100 — gateway declines ("Suspicious payment")

        Reservation reservation = reservationService.reserve(fx.userId(), fx.seatId());

        await().atMost(LOOP_SETTLE).untilAsserted(() ->
                assertThat(reservationRepository.findById(reservation.getId()).orElseThrow().getStatus())
                        .isEqualTo(ReservationStatus.CANCELLED));

        Saga saga = sagaFor(reservation.getId());
        assertThat(saga.getState()).isEqualTo(SagaState.CANCELLED);
        assertThat(paymentRepository.findBySagaId(saga.getId())).hasValueSatisfying(payment -> {
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getGatewayRef()).isNull();
        });
        // Seat/hold release on PAYMENT_FAILED is deliberately NOT asserted: the orchestrator
        // defers it until the m0-audit merge lands owner-aware release (TODO in handlePaymentEvent);
        // today the 10-min TTL + lazy reconciliation free the seat.
    }

    @Test
    @DisplayName("timeout: stale AWAITING_PAYMENT saga → sweeper compensates → RefundConfirmed closes it CANCELLED")
    void timeout_compensationClosesTheSaga() {
        // Seeded directly (SagaTimeoutIT pattern) rather than suppressing the payment
        // listener: the payment component answers every command today, so a reserve()-started
        // saga can never time out. Trade-off: this proves the sweeper's path through the real
        // loop's tail (CancelChargeIfStarted → payment → RefundConfirmed → CANCELLED), but
        // not that the command went unanswered end-to-end.
        Saga saga = seedSagaDirect(SagaState.AWAITING_PAYMENT, ReservationStatus.HELD);
        // The backdate IS the fixture: age past the sweeper's 30s cutoff on the wall clock.
        jdbcTemplate.update("update sagas set created_at = ? where id = ?",
                OffsetDateTime.now().minusSeconds(31), saga.getId());

        await().atMost(LOOP_SETTLE).untilAsserted(() ->
                assertThat(sagaRepository.findById(saga.getId()).orElseThrow().getState())
                        .isEqualTo(SagaState.CANCELLED));

        assertThat(reservationRepository.findById(saga.getReservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        // Charge never started, so compensation found nothing to refund — no payment row,
        // but the CancelChargeIfStarted command demonstrably went through the outbox.
        assertThat(paymentRepository.findBySagaId(saga.getId())).isEmpty();
        assertThat(outboxRowsFor(saga.getId(), KafkaTopics.PAYMENT_CMD))
                .singleElement().satisfies(row -> {
                    assertThat(row.getPayload()).contains(EventTypes.CANCEL_CHARGE_IF_STARTED);
                    assertThat(row.getProcessedAt()).isNotNull();
                });
    }

    // ----------------------------------------------------- senior-signal three

    @Test
    @DisplayName("duplicate PaymentConfirmed: redelivered copy no-ops via dedup, saga stays COMPLETED")
    void duplicatePaymentConfirmed_noOps() throws Exception {
        Fixture fx = seedUserEventSeat(5_000L);
        Reservation reservation = reservationService.reserve(fx.userId(), fx.seatId());
        await().atMost(LOOP_SETTLE).untilAsserted(() ->
                assertThat(reservationRepository.findById(reservation.getId()).orElseThrow().getStatus())
                        .isEqualTo(ReservationStatus.CONFIRMED));
        Saga saga = sagaFor(reservation.getId());

        // Redeliver the REAL PaymentConfirmed envelope — it's sitting in the outbox row the
        // payment component wrote (same eventId ⇒ the dedup path, not the wrong-state guard).
        String original = outboxRowsFor(saga.getId(), KafkaTopics.PAYMENT_EVT)
                .stream().map(OutboxEntry::getPayload)
                .filter(p -> p.contains(EventTypes.PAYMENT_CONFIRMED))
                .findFirst().orElseThrow();
        kafkaTemplate.send(KafkaTopics.PAYMENT_EVT, saga.getId().toString(), original)
                .get(10, TimeUnit.SECONDS);

        // Same key ⇒ same partition ⇒ the sentinel is consumed strictly AFTER the duplicate.
        // Its marker appearing proves the duplicate was consumed and survived. (If dedup were
        // broken, the marker re-insert would PK-violate and wedge the listener in redelivery —
        // this await would time out.)
        UUID sentinelId = sendPaymentEvent(saga.getId(), EventTypes.PAYMENT_CONFIRMED);
        await().atMost(LOOP_SETTLE).untilAsserted(() ->
                assertThat(processedEventRepository.existsById(sentinelId)).isTrue());

        assertThat(sagaRepository.findById(saga.getId()).orElseThrow().getState())
                .isEqualTo(SagaState.COMPLETED);
        assertThat(reservationRepository.findById(reservation.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(paymentRepository.findBySagaId(saga.getId())).isPresent();
    }

    @Test
    @DisplayName("crash-after-commit: mark-lost outbox row republishes; consumer dedup absorbs the replay")
    void republishAfterLostMark_consumerDedups() {
        Fixture fx = seedUserEventSeat(5_000L);
        Reservation reservation = reservationService.reserve(fx.userId(), fx.seatId());
        await().atMost(LOOP_SETTLE).untilAsserted(() ->
                assertThat(reservationRepository.findById(reservation.getId()).orElseThrow().getStatus())
                        .isEqualTo(ReservationStatus.CONFIRMED));
        Saga saga = sagaFor(reservation.getId());

        // Simulate crash-between-send-and-mark: the send happened (payment processed it),
        // but processed_at is lost. At-least-once contract: the row MUST publish again.
        OutboxEntry chargeCard = outboxRowsFor(saga.getId(), KafkaTopics.PAYMENT_CMD)
                .stream().findFirst().orElseThrow();
        assertThat(chargeCard.getProcessedAt()).as("precondition: first publish marked").isNotNull();
        jdbcTemplate.update("update outbox set processed_at = null where id = ?", chargeCard.getId());

        // The live publisher's next tick re-sends and re-marks — that's the republish.
        await().atMost(LOOP_SETTLE).untilAsserted(() ->
                assertThat(outboxEntryRepository.findById(chargeCard.getId()).orElseThrow().getProcessedAt())
                        .isNotNull());

        // Sentinel AFTER the re-mark (send-then-mark order guarantees the replay is already
        // on the partition): its dedup marker proves the payment listener consumed the replay.
        UUID sentinelId = sendPaymentCommand(saga.getId());
        await().atMost(LOOP_SETTLE).untilAsserted(() ->
                assertThat(paymentProcessedEventRepository.existsById(sentinelId)).isTrue());

        // The replayed ChargeCard hit payment's own dedup table: still exactly one charge.
        assertThat(paymentRepository.findAll().stream()
                .filter(p -> saga.getId().equals(p.getSagaId())).count()).isEqualTo(1);
        assertThat(sagaRepository.findById(saga.getId()).orElseThrow().getState())
                .isEqualTo(SagaState.COMPLETED);
    }

    @Test
    @DisplayName("out-of-order PaymentConfirmed: wrong-state guard skips it, marker makes the skip permanent")
    void outOfOrderEvent_skippedByWrongStateGuard() {
        // COMPENSATING saga: PaymentConfirmed is only legal from AWAITING_PAYMENT, so this
        // models the late/out-of-order arrival (ADR 0003 policy: log + skip, never park).
        Saga saga = seedSagaDirect(SagaState.COMPENSATING, ReservationStatus.CANCELLED);

        UUID eventId = sendPaymentEvent(saga.getId(), EventTypes.PAYMENT_CONFIRMED);

        // Marker saved BEFORE the state check — the skip is permanent on redelivery.
        await().atMost(LOOP_SETTLE).untilAsserted(() ->
                assertThat(processedEventRepository.existsById(eventId)).isTrue());

        assertThat(sagaRepository.findById(saga.getId()).orElseThrow().getState())
                .isEqualTo(SagaState.COMPENSATING);
        assertThat(reservationRepository.findById(saga.getReservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    // -------------------------------------------------------------- helpers

    private record Fixture(UUID userId, UUID seatId) {}

    /** Seeds user + event + one AVAILABLE seat; price picks the gateway outcome (> $100 declines). */
    private Fixture seedUserEventSeat(long priceCents) {
        User user = userRepository.save(User.builder()
                .email("e2e-" + UUID.randomUUID() + "@test.local")
                .passwordHash(passwordEncoder.encode("password12345"))
                .displayName("e2e-tester")
                .build());
        Seat seat = seedSeat(priceCents, SeatStatus.AVAILABLE);
        return new Fixture(user.getId(), seat.getId());
    }

    /** Direct saga seed (SagaTimeoutIT pattern) — fresh created_at, no outbox rows, no commands in flight. */
    private Saga seedSagaDirect(SagaState sagaState, ReservationStatus reservationStatus) {
        User user = userRepository.save(User.builder()
                .email("e2e-" + UUID.randomUUID() + "@test.local")
                .passwordHash(passwordEncoder.encode("password12345"))
                .displayName("e2e-tester")
                .build());
        Seat seat = seedSeat(5_000L, SeatStatus.HELD);
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

    private Seat seedSeat(long priceCents, SeatStatus status) {
        Event event = eventRepository.save(Event.builder()
                .name("E2E Test Event")
                .venue("Test Arena")
                .startsAt(OffsetDateTime.now().plusDays(30))
                .endsAt(OffsetDateTime.now().plusDays(30).plusHours(2))
                .build());
        return seatRepository.save(Seat.builder()
                .event(event)
                .seatLabel("E-1")
                .priceCents(priceCents)
                .status(status)
                .version(0L)
                .build());
    }

    private Saga sagaFor(UUID reservationId) {
        return sagaRepository.findAll().stream()
                .filter(s -> reservationId.equals(s.getReservationId()))
                .findFirst().orElseThrow();
    }

    private List<OutboxEntry> outboxRowsFor(UUID sagaId, String topic) {
        return outboxEntryRepository.findAll().stream()
                .filter(row -> sagaId.equals(row.getAggregateId()))
                .filter(row -> topic.equals(row.getTopic()))
                .toList();
    }

    /** Sends a fresh envelope onto payment.evt keyed by sagaId; returns its eventId. */
    private UUID sendPaymentEvent(UUID sagaId, String eventType) {
        return sendEnvelope(KafkaTopics.PAYMENT_EVT, sagaId, sagaId, eventType);
    }

    /**
     * Sentinel for payment.cmd: keyed by {@code partitionKeySagaId} (ordering), but the
     * ENVELOPE names an unknown saga so the payment side just writes its dedup marker and
     * emits a RefundConfirmed the orchestrator safely skips as unknown.
     */
    private UUID sendPaymentCommand(UUID partitionKeySagaId) {
        return sendEnvelope(KafkaTopics.PAYMENT_CMD, partitionKeySagaId, UUID.randomUUID(),
                EventTypes.CANCEL_CHARGE_IF_STARTED);
    }

    private UUID sendEnvelope(String topic, UUID partitionKey, UUID envelopeSagaId, String eventType) {
        UUID eventId = UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(
                eventId, eventType, 1, OffsetDateTime.now(), envelopeSagaId,
                objectMapper.valueToTree(new PaymentConfirmed()));
        try {
            kafkaTemplate.send(topic, partitionKey.toString(), objectMapper.writeValueAsString(envelope))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("test send to " + topic + " failed", e);
        }
        return eventId;
    }
}
