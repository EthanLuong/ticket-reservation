package com.ethanluong.ticketreservation;

import com.ethanluong.ticketreservation.api.exception.SeatNotAvailableException;
import com.ethanluong.ticketreservation.domain.entity.Event;
import com.ethanluong.ticketreservation.domain.entity.Seat;
import com.ethanluong.ticketreservation.domain.entity.User;
import com.ethanluong.ticketreservation.domain.repository.EventRepository;
import com.ethanluong.ticketreservation.domain.repository.ReservationRepository;
import com.ethanluong.ticketreservation.domain.repository.SeatRepository;
import com.ethanluong.ticketreservation.domain.repository.UserRepository;
import com.ethanluong.ticketreservation.domain.type.SeatStatus;
import com.ethanluong.ticketreservation.service.ReservationService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the seat-reservation concurrency invariant: at most one
 * caller may reserve a given seat, even under a hot race.
 *
 * <p>This test runs without a wrapping {@code @Transactional} — each racing
 * thread's {@code reserve()} call opens its own transaction (from the service
 * layer's own {@code @Transactional}), which is required for the
 * optimistic-lock semantics to actually manifest at commit time.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Slf4j
class SeatReservationConcurrencyIT {

    private static final int THREAD_COUNT = 10;

    @Autowired private ReservationService reservationService;
    @Autowired private UserRepository userRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private UUID seatId;
    private List<UUID> userIds;

    @BeforeEach
    void seed() {
        userIds = new ArrayList<>(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            User u = userRepository.save(User.builder()
                    .email("racer-" + i + "-" + UUID.randomUUID() + "@test.local")
                    .passwordHash(passwordEncoder.encode("password12345"))
                    .displayName("Racer " + i)
                    .build());
            userIds.add(u.getId());
        }

        Event event = eventRepository.save(Event.builder()
                .name("Concurrency Test Event")
                .venue("Test Arena")
                .startsAt(OffsetDateTime.now().plusDays(30))
                .endsAt(OffsetDateTime.now().plusDays(30).plusHours(2))
                .build());

        Seat seat = seatRepository.save(Seat.builder()
                .event(event)
                .seatLabel("A-1")
                .priceCents(5000L)
                .status(SeatStatus.AVAILABLE)
                .version(0L)
                .build());
        seatId = seat.getId();
    }

    @AfterEach
    void cleanup() {
        reservationRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    /** Categorised outcome of a single thread's reserve() attempt. */
    enum Outcome {
        /** The thread's reserve() returned a Reservation. */
        SUCCESS,
        /** App-layer fast-fail: seat was already held/sold when this thread checked. */
        FAILURE_NOT_AVAILABLE,
        /** DB-layer catch: @Version collision at flush — another thread committed first. */
        FAILURE_OPTIMISTIC_LOCK,
        /** DB level violation */
        FAILURE_DATA_INTEGRITY,
        /** Anything else — indicates a bug, not a correctly-lost race. */
        FAILURE_OTHER
    }

    /**
     * Releases {@code threadCount} threads through a start-gate latch so they
     * all attempt {@code reserve()} at (approximately) the same instant.
     * Returns the outcomes in order of future completion.
     */
    private List<Outcome> runRace(int threadCount) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>(threadCount);

        try {
            for (int i = 0; i < threadCount; i++) {
                UUID userId = userIds.get(i);
                futures.add(pool.submit(() -> {
                    startGate.await();
                    try {
                        reservationService.reserve(userId, seatId);
                        return Outcome.SUCCESS;
                    } catch (SeatNotAvailableException e) {
                        return Outcome.FAILURE_NOT_AVAILABLE;
                    } catch (OptimisticLockingFailureException e) {
                        return Outcome.FAILURE_OPTIMISTIC_LOCK;
                    } catch (DataIntegrityViolationException e) {
                        return Outcome.FAILURE_DATA_INTEGRITY;
                    } catch (Exception e) {
                        log.warn("Unexpected failure in racing thread", e);
                        return Outcome.FAILURE_OTHER;
                    }
                }));
            }

            startGate.countDown();

            List<Outcome> results = new ArrayList<>(threadCount);
            for (Future<Outcome> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Sanity: single reserve() succeeds, seat flips HELD, version bumps")
    void singleThreadReserveSucceeds() {
        var reservation = reservationService.reserve(userIds.get(0), seatId);

        assertThat(reservation.getSeat().getId()).isEqualTo(seatId);
        assertThat(reservationRepository.count()).isEqualTo(1);

        var seat = seatRepository.findById(seatId).orElseThrow();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(seat.getVersion()).isEqualTo(1L);
    }


    @Test
    @DisplayName("10 threads race the same seat — exactly 1 succeeds")
    void tenThreadsRaceSameSeat_exactlyOneWins() throws Exception {
        List<Outcome> outcomes = runRace(THREAD_COUNT);
        assertThat(outcomes).filteredOn(val -> val.equals(Outcome.SUCCESS)).hasSize(1);
        assertThat(outcomes).noneMatch(val -> val.equals(Outcome.FAILURE_OTHER));

        assertThat(reservationRepository.count()).isEqualTo(1);
        var seat = seatRepository.findById(seatId).orElseThrow();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(seat.getVersion()).isEqualTo(1L);
    }

}
