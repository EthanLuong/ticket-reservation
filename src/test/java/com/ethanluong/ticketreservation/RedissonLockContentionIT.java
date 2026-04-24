package com.ethanluong.ticketreservation;

import com.ethanluong.ticketreservation.api.exception.SeatContentionException;
import com.ethanluong.ticketreservation.domain.entity.Event;
import com.ethanluong.ticketreservation.domain.entity.Reservation;
import com.ethanluong.ticketreservation.domain.entity.Seat;
import com.ethanluong.ticketreservation.domain.entity.User;
import com.ethanluong.ticketreservation.domain.repository.EventRepository;
import com.ethanluong.ticketreservation.domain.repository.ReservationRepository;
import com.ethanluong.ticketreservation.domain.repository.SeatRepository;
import com.ethanluong.ticketreservation.domain.repository.UserRepository;
import com.ethanluong.ticketreservation.domain.type.SeatStatus;
import com.ethanluong.ticketreservation.service.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for the Redisson {@code RLock} critical-section serialization.
 * When another caller holds {@code lock:seat:{seatId}}, reserve() must fail fast
 * with {@link SeatContentionException} within the short lock-wait window
 * ({@code tryLock(100ms wait, 2s lease)}).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedissonLockContentionIT {

    @Autowired private ReservationService reservationService;
    @Autowired private UserRepository userRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RedissonClient redisson;

    private UUID userId;
    private UUID seatId;

    @BeforeEach
    void seed() {
        User user = userRepository.save(User.builder()
                .email("lock-" + UUID.randomUUID() + "@test.local")
                .passwordHash(passwordEncoder.encode("password12345"))
                .displayName("Locker")
                .build());
        userId = user.getId();

        Event event = eventRepository.save(Event.builder()
                .name("Lock Test Event")
                .venue("Test Arena")
                .startsAt(OffsetDateTime.now().plusDays(30))
                .endsAt(OffsetDateTime.now().plusDays(30).plusHours(2))
                .build());

        Seat seat = seatRepository.save(Seat.builder()
                .event(event)
                .seatLabel("C-1")
                .priceCents(5000L)
                .status(SeatStatus.AVAILABLE)
                .version(0L)
                .build());
        seatId = seat.getId();
    }

    @AfterEach
    void cleanup() {
        redis.delete("hold:seat:" + seatId);
        // In case a prior test didn't release the lock key, clear it too.
        redis.delete("lock:seat:" + seatId);
        reservationRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("reserve() fast-fails with SeatContentionException when RLock is held on a different thread")
    void reserve_throwsContentionWhenLockHeld() throws Exception {
        // Redisson RLock is reentrant — holding on the SAME thread would let
        // reserve()'s internal tryLock reacquire (same owner). To simulate
        // contention we must hold from a different thread.
        ExecutorService bg = Executors.newSingleThreadExecutor();
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        bg.submit(() -> {
            RLock lock = redisson.getLock("lock:seat:" + seatId);
            lock.lock();
            try {
                acquired.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
            return null;
        });

        try {
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            assertThatThrownBy(() -> reservationService.reserve(userId, seatId))
                    .isInstanceOf(SeatContentionException.class);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

            // tryLock waits up to 100ms. Allow 500ms for JVM scheduling /
            // Testcontainers latency. Point: fast-fail, not a 2s lease wait.
            assertThat(elapsedMs).isLessThan(500L);

            // No hold was written (lock acquisition failed before the SET NX).
            assertThat(redis.hasKey("hold:seat:" + seatId)).isFalse();
            assertThat(reservationRepository.count()).isZero();
        } finally {
            release.countDown();
            bg.shutdown();
            bg.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("reserve() succeeds normally once the external lock is released")
    void reserve_succeedsAfterLockReleased() {
        RLock externalLock = redisson.getLock("lock:seat:" + seatId);
        externalLock.lock();
        externalLock.unlock(); // release immediately — lock is just a gate

        Reservation r = reservationService.reserve(userId, seatId);
        assertThat(r).isNotNull();
        assertThat(redis.hasKey("hold:seat:" + seatId)).isTrue();
    }
}
