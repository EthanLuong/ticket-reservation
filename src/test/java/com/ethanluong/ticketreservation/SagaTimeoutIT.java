package com.ethanluong.ticketreservation;

import com.ethanluong.ticketreservation.domain.entity.Event;
import com.ethanluong.ticketreservation.domain.entity.OutboxEntry;
import com.ethanluong.ticketreservation.domain.entity.Reservation;
import com.ethanluong.ticketreservation.domain.entity.Saga;
import com.ethanluong.ticketreservation.domain.entity.Seat;
import com.ethanluong.ticketreservation.domain.entity.User;
import com.ethanluong.ticketreservation.domain.repository.EventRepository;
import com.ethanluong.ticketreservation.domain.repository.OutboxEntryRepository;
import com.ethanluong.ticketreservation.domain.repository.ReservationRepository;
import com.ethanluong.ticketreservation.domain.repository.SagaRepository;
import com.ethanluong.ticketreservation.domain.repository.SeatRepository;
import com.ethanluong.ticketreservation.domain.repository.UserRepository;
import com.ethanluong.ticketreservation.domain.type.ReservationStatus;
import com.ethanluong.ticketreservation.domain.type.SagaState;
import com.ethanluong.ticketreservation.domain.type.SeatStatus;
import com.ethanluong.ticketreservation.saga.SagaOrchestrator;
import com.ethanluong.ticketreservation.saga.SagaTimeoutSweeper;
import com.ethanluong.ticketreservation.saga.events.EventTypes;
import com.ethanluong.ticketreservation.saga.events.KafkaTopics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Timeout sweeper transition + cutoff query against real Postgres.
 *
 * Two clocks are in play: created_at is stamped by the DATABASE (insertable = false,
 * DB default), while the sweep cutoff comes from the injected (here: fixed) Clock.
 * Rows are therefore aged by backdating created_at with SQL — the entity can't set it.
 * FIXED_NOW is deliberately in the past so freshly-inserted rows (DB "now") always
 * look YOUNGER than any cutoff and never get swept by accident.
 *
 * app.scheduling.enabled=false: without it the real @Scheduled sweeper runs once a
 * second in the background of this test and races the backdated fixtures. Tests call
 * sweep() / timeoutSaga() directly.
 */
@SpringBootTest(properties = "app.scheduling.enabled=false")
@Import(TestcontainersConfiguration.class)
class SagaTimeoutIT {

    /** All time is relative to this instant, never the wall clock. */
    private static final Instant FIXED_NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
    /** Mirrors SagaTimeoutSweeper.PAYMENT_TIMEOUT (package-private there). */
    private static final Duration PAYMENT_TIMEOUT = Duration.ofSeconds(30);

    /** Overrides the production Clock bean for this test class only. */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private SagaOrchestrator sagaOrchestrator;
    @Autowired private SagaTimeoutSweeper sweeper;
    @Autowired private SagaRepository sagaRepository;
    @Autowired private OutboxEntryRepository outboxEntryRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        // FK order: sagas reference reservations — children first (mirror of insert order)
        sagaRepository.deleteAllInBatch();
        outboxEntryRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("cutoff query: only stale AWAITING_PAYMENT sagas are returned (30s strict)")
    void query_returnsOnlyStaleAwaitingPayment() {
        Saga stale = seedSaga(SagaState.AWAITING_PAYMENT, ReservationStatus.HELD);
        Saga fresh = seedSaga(SagaState.AWAITING_PAYMENT, ReservationStatus.HELD);
        Saga completed = seedSaga(SagaState.COMPLETED, ReservationStatus.CONFIRMED);
        backdate(stale.getId(), NOW.minusSeconds(31));
        backdate(fresh.getId(), NOW.minusSeconds(29));
        backdate(completed.getId(), NOW.minusSeconds(31));

        List<UUID> ids = sagaRepository.findIdsByStateAndCreatedAtBefore(
                SagaState.AWAITING_PAYMENT, NOW.minus(PAYMENT_TIMEOUT));

        assertThat(ids).containsExactly(stale.getId());
    }

    @Test
    @DisplayName("timeoutSaga: AWAITING_PAYMENT → COMPENSATING, reservation CANCELLED, CancelChargeIfStarted outbox row in same tx")
    void timeoutSaga_compensatesAndWritesOutbox() {
        Saga saga = seedSaga(SagaState.AWAITING_PAYMENT, ReservationStatus.HELD);

        sagaOrchestrator.timeoutSaga(saga.getId());

        assertThat(sagaRepository.findById(saga.getId()).orElseThrow().getState())
                .isEqualTo(SagaState.COMPENSATING);
        assertThat(reservationRepository.findById(saga.getReservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);

        List<OutboxEntry> rows = outboxRowsFor(saga.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getTopic()).isEqualTo(KafkaTopics.PAYMENT_CMD);
        // ADR 0003: the serialized ENVELOPE rides in the outbox — discriminator + sagaId inside the JSON
        assertThat(rows.getFirst().getPayload())
                .contains(EventTypes.CANCEL_CHARGE_IF_STARTED)
                .contains(saga.getId().toString());
    }

    @Test
    @DisplayName("timeoutSaga on a COMPLETED saga: skip — nothing changes, no outbox row (paid mid-sweep race)")
    void timeoutSaga_completedSaga_skipped() {
        Saga saga = seedSaga(SagaState.COMPLETED, ReservationStatus.CONFIRMED);

        sagaOrchestrator.timeoutSaga(saga.getId());

        assertThat(sagaRepository.findById(saga.getId()).orElseThrow().getState())
                .isEqualTo(SagaState.COMPLETED);
        assertThat(reservationRepository.findById(saga.getReservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(outboxRowsFor(saga.getId())).isEmpty();
    }

    @Test
    @DisplayName("timeoutSaga on a missing saga: logs and returns, never throws")
    void timeoutSaga_missingSaga_noThrow() {
        UUID ghost = UUID.randomUUID();

        assertThatCode(() -> sagaOrchestrator.timeoutSaga(ghost)).doesNotThrowAnyException();
        assertThat(outboxRowsFor(ghost)).isEmpty();
    }

    @Test
    @DisplayName("sweep() end-to-end: times out only the stale saga, leaves the fresh one alone")
    void sweep_timesOutOnlyStaleSagas() {
        Saga stale = seedSaga(SagaState.AWAITING_PAYMENT, ReservationStatus.HELD);
        Saga fresh = seedSaga(SagaState.AWAITING_PAYMENT, ReservationStatus.HELD);
        backdate(stale.getId(), NOW.minusSeconds(31));
        backdate(fresh.getId(), NOW.minusSeconds(29));

        sweeper.sweep();

        assertThat(sagaRepository.findById(stale.getId()).orElseThrow().getState())
                .isEqualTo(SagaState.COMPENSATING);
        assertThat(reservationRepository.findById(stale.getReservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(sagaRepository.findById(fresh.getId()).orElseThrow().getState())
                .isEqualTo(SagaState.AWAITING_PAYMENT);
        assertThat(reservationRepository.findById(fresh.getReservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.HELD);
    }

    // -------------------------------------------------------------- helpers

    /** Seeds user → event/seat → reservation → saga directly (no reserve() flow — keeps the outbox empty of ChargeCard rows). */
    private Saga seedSaga(SagaState sagaState, ReservationStatus reservationStatus) {
        User user = userRepository.save(User.builder()
                .email("timeout-" + UUID.randomUUID() + "@test.local")
                .passwordHash(passwordEncoder.encode("password12345"))
                .displayName("timeout-tester")
                .build());
        Event event = eventRepository.save(Event.builder()
                .name("Timeout Test Event")
                .venue("Test Arena")
                .startsAt(NOW.plusDays(30))
                .endsAt(NOW.plusDays(30).plusHours(2))
                .build());
        Seat seat = seatRepository.save(Seat.builder()
                .event(event)
                .seatLabel("T-1")
                .priceCents(5000L)
                .status(SeatStatus.HELD)
                .version(0L)
                .build());
        Reservation reservation = reservationRepository.save(Reservation.builder()
                .id(UUID.randomUUID())
                .user(user)
                .seat(seat)
                .status(reservationStatus)
                .expiresAt(NOW.plusMinutes(10))
                .build());
        return sagaRepository.save(Saga.builder()
                .id(UUID.randomUUID())
                .type("BOOKING")
                .reservationId(reservation.getId())
                .state(sagaState)
                .build());
    }

    /** created_at is DB-stamped (insertable = false) — aging a row means SQL behind the entity's back. */
    private void backdate(UUID sagaId, OffsetDateTime createdAt) {
        jdbcTemplate.update("update sagas set created_at = ? where id = ?", createdAt, sagaId);
    }

    private List<OutboxEntry> outboxRowsFor(UUID sagaId) {
        return outboxEntryRepository.findAll().stream()
                .filter(row -> sagaId.equals(row.getAggregateId()))
                .toList();
    }
}
