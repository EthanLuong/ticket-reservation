package com.ethanluong.ticketreservation;

import com.ethanluong.ticketreservation.api.exception.SeatNotAvailableException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for Redis-backed TTL holds. Verifies the post-commit-#2
 * behavior: on reserve(), a key at {@code hold:seat:{seatId}} is written
 * with a TTL matching the business rule; collision is detected by
 * {@code SET NX}; cancel() removes the key so the seat is re-reservable.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisTTLHoldIT {

    private static final long EXPECTED_TTL_SECONDS = 600L; // 10 minutes

    @Autowired private ReservationService reservationService;
    @Autowired private UserRepository userRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private StringRedisTemplate redis;

    private UUID userId;
    private UUID otherUserId;
    private UUID seatId;

    @BeforeEach
    void seed() {
        User user = userRepository.save(User.builder()
                .email("holder-" + UUID.randomUUID() + "@test.local")
                .passwordHash(passwordEncoder.encode("password12345"))
                .displayName("Holder")
                .build());
        userId = user.getId();

        User other = userRepository.save(User.builder()
                .email("other-" + UUID.randomUUID() + "@test.local")
                .passwordHash(passwordEncoder.encode("password12345"))
                .displayName("Other")
                .build());
        otherUserId = other.getId();

        Event event = eventRepository.save(Event.builder()
                .name("TTL Test Event")
                .venue("Test Arena")
                .startsAt(OffsetDateTime.now().plusDays(30))
                .endsAt(OffsetDateTime.now().plusDays(30).plusHours(2))
                .build());

        Seat seat = seatRepository.save(Seat.builder()
                .event(event)
                .seatLabel("B-1")
                .priceCents(5000L)
                .status(SeatStatus.AVAILABLE)
                .version(0L)
                .build());
        seatId = seat.getId();
    }

    @AfterEach
    void cleanup() {
        redis.delete(holdKey(seatId));
        reservationRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private String holdKey(UUID seat) {
        return "hold:seat:" + seat;
    }

    @Test
    @DisplayName("reserve() writes hold:seat:{seatId} with TTL ≈ 600s")
    void reserve_writesRedisKeyWithTtl() {
        Reservation reservation = reservationService.reserve(userId, seatId);

        String key = holdKey(seatId);
        assertThat(redis.hasKey(key)).isTrue();

        // Value should carry the reservation id so a future cancel/lock
        // operation could be release-by-owner aware.
        assertThat(redis.opsForValue().get(key))
                .isEqualTo(reservation.getId().toString());

        // TTL tolerance: 10s window for test scheduling latency.
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isBetween(EXPECTED_TTL_SECONDS - 10, EXPECTED_TTL_SECONDS);
    }

    @Test
    @DisplayName("second reserve() for held seat fast-fails via Redis SET NX")
    void secondReserve_fastFails() {
        reservationService.reserve(userId, seatId);

        assertThatThrownBy(() -> reservationService.reserve(otherUserId, seatId))
                .isInstanceOf(SeatNotAvailableException.class);

        // Only the first reservation exists; no ghost DB row from the failed attempt.
        assertThat(reservationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("cancel() removes the hold key so the seat is re-reservable")
    void cancel_deletesHoldKeyAndAllowsReReserve() {
        Reservation first = reservationService.reserve(userId, seatId);
        reservationService.cancel(userId, first.getId());

        // Key is gone — re-reservation is allowed and gets a fresh hold.
        assertThat(redis.hasKey(holdKey(seatId))).isFalse();

        Reservation second = reservationService.reserve(otherUserId, seatId);
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(redis.opsForValue().get(holdKey(seatId)))
                .isEqualTo(second.getId().toString());
    }
}
