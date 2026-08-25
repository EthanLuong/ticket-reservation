package com.ethanluong.ticketreservation;

import com.ethanluong.ticketreservation.api.dto.ReservationResponse;
import com.ethanluong.ticketreservation.domain.entity.Event;
import com.ethanluong.ticketreservation.domain.entity.Seat;
import com.ethanluong.ticketreservation.domain.entity.User;
import com.ethanluong.ticketreservation.domain.repository.EventRepository;
import com.ethanluong.ticketreservation.domain.repository.IdempotencyRecordRepository;
import com.ethanluong.ticketreservation.domain.repository.OutboxEntryRepository;
import com.ethanluong.ticketreservation.domain.repository.ReservationRepository;
import com.ethanluong.ticketreservation.domain.repository.SagaRepository;
import com.ethanluong.ticketreservation.domain.repository.SeatRepository;
import com.ethanluong.ticketreservation.domain.repository.UserRepository;
import com.ethanluong.ticketreservation.domain.type.IdempotencyStatus;
import com.ethanluong.ticketreservation.domain.type.SeatStatus;
import com.ethanluong.ticketreservation.service.IdempotencyService;
import com.ethanluong.ticketreservation.service.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Card Step 3 — proves the Idempotency-Key contract at the service seam (the same seam
 * every other IT drives; the controller adds only header binding + hashing on top).
 *
 * Covered: replay (completed duplicate returns the stored response verbatim, no
 * re-execution), the in-flight race (deterministic, latch-choreographed: the duplicate
 * arrives WHILE the first request is mid-handler and must 409 without executing),
 * poisoned reuse (same key, different payload → 422, handler untouched), and the
 * failure path (handler throws → FAILED → a retry with the same key re-executes via
 * the guarded FAILED→IN_PROGRESS flip — which also exercises the @Modifying query's
 * own transaction at runtime).
 *
 * Runs in the scheduling-off shared context: reserve() writes the saga + outbox rows
 * but no publisher drains them — idempotency semantics don't need the loop.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotencyIT {

    private static final String ENDPOINT = "POST /api/reservations";

    @Autowired private IdempotencyService idempotency;
    @Autowired private ReservationService reservationService;
    @Autowired private IdempotencyRecordRepository idempotencyRecords;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private SagaRepository sagaRepository;
    @Autowired private OutboxEntryRepository outboxEntryRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @AfterEach
    void cleanup() {
        idempotencyRecords.deleteAllInBatch();
        sagaRepository.deleteAllInBatch();
        outboxEntryRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("replay: completed duplicate returns the stored response verbatim; exactly one reservation")
    void completedDuplicate_replaysStoredResponse() {
        Fixture fx = seed();
        String key = UUID.randomUUID().toString();
        String hash = IdempotencyService.sha256("canonical-body");

        ResponseEntity<String> first = idempotency.execute(
                fx.userId(), ENDPOINT, key, hash, reserveSupplier(fx));
        ResponseEntity<String> second = idempotency.execute(
                fx.userId(), ENDPOINT, key, hash, failTheTestSupplier());

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(second.getBody()).isEqualTo(first.getBody()); // byte-identical replay
        assertThat(reservationRepository.count()).isEqualTo(1);
        assertThat(sagaRepository.count()).isEqualTo(1);
        assertThat(recordFor(fx.userId(), key).getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
    }

    @Test
    @DisplayName("in-flight race: duplicate arriving mid-execution gets 409 + Retry-After, never executes")
    void inFlightDuplicate_gets409() throws Exception {
        Fixture fx = seed();
        String key = UUID.randomUUID().toString();
        String hash = IdempotencyService.sha256("canonical-body");

        // Choreography instead of a timing-based race: the winner's handler parks inside
        // execution until the duplicate has been answered — the IN_PROGRESS window is held
        // open deliberately, so the 409 outcome is deterministic, not probabilistic.
        CountDownLatch winnerInHandler = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ResponseEntity<String>> winner = pool.submit(() ->
                    idempotency.execute(fx.userId(), ENDPOINT, key, hash, () -> {
                        winnerInHandler.countDown();
                        awaitOrFail(releaseWinner);
                        return reserveSupplier(fx).get();
                    }));

            assertThat(winnerInHandler.await(10, TimeUnit.SECONDS))
                    .as("winner reached its handler").isTrue();

            ResponseEntity<String> duplicate = idempotency.execute(
                    fx.userId(), ENDPOINT, key, hash, failTheTestSupplier());

            assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
            assertThat(duplicate.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");

            releaseWinner.countDown();
            assertThat(winner.get(20, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        } finally {
            releaseWinner.countDown(); // never leave the winner parked on a failed assertion
            pool.shutdownNow();
        }
        assertThat(reservationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("poisoned reuse: same key + different payload → 422, handler never runs, no second reservation")
    void reusedKeyDifferentPayload_gets422() {
        Fixture fx = seed();
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = idempotency.execute(fx.userId(), ENDPOINT, key,
                IdempotencyService.sha256("body-A"), reserveSupplier(fx));
        assertThat(first.getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> poisoned = idempotency.execute(fx.userId(), ENDPOINT, key,
                IdempotencyService.sha256("body-B"), failTheTestSupplier());

        assertThat(poisoned.getStatusCode().value()).isEqualTo(422);
        assertThat(reservationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("failure path: handler throws → FAILED (not cached); same-key retry re-executes and completes")
    void failedExecution_retryWithSameKeyReExecutes() {
        Fixture fx = seed();
        String key = UUID.randomUUID().toString();
        String hash = IdempotencyService.sha256("canonical-body");

        assertThatThrownBy(() -> idempotency.execute(fx.userId(), ENDPOINT, key, hash,
                () -> { throw new IllegalStateException("gateway exploded"); }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("gateway exploded");
        assertThat(recordFor(fx.userId(), key).getStatus()).isEqualTo(IdempotencyStatus.FAILED);
        assertThat(reservationRepository.count()).isZero();

        // Retry takes the guarded FAILED→IN_PROGRESS flip, then executes for real.
        ResponseEntity<String> retry = idempotency.execute(
                fx.userId(), ENDPOINT, key, hash, reserveSupplier(fx));

        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(recordFor(fx.userId(), key).getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(reservationRepository.count()).isEqualTo(1);
    }

    // -------------------------------------------------------------- helpers

    private record Fixture(UUID userId, UUID seatId) {}

    private Fixture seed() {
        User user = userRepository.save(User.builder()
                .email("idem-" + UUID.randomUUID() + "@test.local")
                .passwordHash(passwordEncoder.encode("password12345"))
                .displayName("idem-tester")
                .build());
        Event event = eventRepository.save(Event.builder()
                .name("Idempotency Test Event")
                .venue("Test Arena")
                .startsAt(OffsetDateTime.now().plusDays(30))
                .endsAt(OffsetDateTime.now().plusDays(30).plusHours(2))
                .build());
        Seat seat = seatRepository.save(Seat.builder()
                .event(event)
                .seatLabel("I-1")
                .priceCents(5_000L)
                .status(SeatStatus.AVAILABLE)
                .version(0L)
                .build());
        return new Fixture(user.getId(), seat.getId());
    }

    /** Mirrors the controller's supplier: 201 + ReservationResponse from the real reserve(). */
    private Supplier<ResponseEntity<?>> reserveSupplier(Fixture fx) {
        return () -> ResponseEntity.status(201)
                .body(ReservationResponse.from(reservationService.reserve(fx.userId(), fx.seatId())));
    }

    /** Supplier that must never run — dedup paths answer without executing the handler. */
    private Supplier<ResponseEntity<?>> failTheTestSupplier() {
        AtomicBoolean invoked = new AtomicBoolean();
        return () -> {
            invoked.set(true);
            throw new AssertionError("handler executed on a path that must not execute it");
        };
    }

    private com.ethanluong.ticketreservation.domain.entity.IdempotencyRecord recordFor(UUID userId, String key) {
        return idempotencyRecords.findByUserIdAndEndpointAndIdempotencyKey(userId, ENDPOINT, key).orElseThrow();
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("latch never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
