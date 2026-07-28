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
import com.ethanluong.ticketreservation.domain.type.ReservationStatus;
import com.ethanluong.ticketreservation.domain.type.SeatStatus;
import com.ethanluong.ticketreservation.service.ReservationHoldStore;
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

import java.time.Duration;
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
    @Autowired private ReservationHoldStore reservationHoldStore;

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

    @Test
    @DisplayName("release() wrong owner fails to release hold")
    void release_wrongOwner() {
        UUID reserveId = UUID.randomUUID();
        reservationHoldStore.tryHold(seatId, reserveId, Duration.ofSeconds(10));

        assertThat(reservationHoldStore.release(seatId, UUID.randomUUID())).isFalse();
        assertThat(redis.opsForValue().get(holdKey(seatId))).isEqualTo(reserveId.toString());

        assertThat(reservationHoldStore.release(seatId, reserveId)).isTrue();
        assertThat(redis.hasKey(holdKey(seatId))).isFalse();
    }


    @Test
    @DisplayName("reserve() reconciles stale HELD row when the hold has genuinely expired")
    void reserve_reconcilesStaleHeldRowOnExpiredTtl() {
        // Genuine expiry = key gone AND expires_at in the past (I2: the DB is the
        // authority; a missing key alone is not expiry).
        Reservation stale = reservationService.reserve(userId, seatId);
        redis.delete(holdKey(seatId));
        backdateExpiry(stale.getId());

        // Another user tries to reserve — without reconciliation this would 409
        // (seat.status=HELD in Postgres). With reconciliation, the stale row is
        // flipped to EXPIRED and the new reservation wins.
        Reservation fresh = reservationService.reserve(otherUserId, seatId);

        assertThat(fresh.getId()).isNotEqualTo(stale.getId());
        var reloadedStale = reservationRepository.findById(stale.getId()).orElseThrow();
        assertThat(reloadedStale.getStatus()).isEqualTo(ReservationStatus.EXPIRED);

        var seat = seatRepository.findById(seatId).orElseThrow();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD); // the new hold's state
    }

    @Test
    @DisplayName("myReservations() lazy-reconciles HELD rows whose hold has genuinely expired")
    void myReservations_lazilyReconcilesExpiredHolds() {
        Reservation r = reservationService.reserve(userId, seatId);
        redis.delete(holdKey(seatId));
        backdateExpiry(r.getId());

        var list = reservationService.myReservations(userId);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getStatus()).isEqualTo(ReservationStatus.EXPIRED);

        // Seat is freed so a new reservation can take it.
        var seat = seatRepository.findById(seatId).orElseThrow();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    // ---------------------------------------------------------- I2 / C2 (m0 audit)

    @Test
    @DisplayName("I2: key loss alone does not expire a live hold on read — Postgres expires_at is the authority")
    void keyLossAlone_doesNotExpireHoldOnRead() {
        Reservation r = reservationService.reserve(userId, seatId);
        redis.delete(holdKey(seatId)); // Redis restart/failover/eviction — hold is still live by DB

        var list = reservationService.myReservations(userId);

        assertThat(list.get(0).getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(reservationRepository.findById(r.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.HELD);
        assertThat(seatRepository.findById(seatId).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.HELD);
    }

    @Test
    @DisplayName("I2: a competitor's reserve() cannot steal a live hold whose key was lost")
    void keyLossAlone_reserveRefusesToStealLiveHold() {
        Reservation victim = reservationService.reserve(userId, seatId);
        redis.delete(holdKey(seatId)); // key lost, but expires_at is still 10 min out

        assertThatThrownBy(() -> reservationService.reserve(otherUserId, seatId))
                .isInstanceOf(SeatNotAvailableException.class);

        // Victim untouched; the failed attempt compensated its own key and left no ghost row.
        assertThat(reservationRepository.findById(victim.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.HELD);
        assertThat(reservationRepository.count()).isEqualTo(1);
        assertThat(redis.hasKey(holdKey(seatId))).isFalse();
    }

    @Test
    @DisplayName("C5 backstop: the partial unique index makes a second live hold unrepresentable")
    void partialIndex_rejectsSecondActiveHoldRow() {
        // C2's nightmare state (two HELD rows, one seat) cannot exist in this schema:
        // idx_reservations_active_seat is the last line of defense under every race
        // above it, and the reconciliation code leans on that (no competing-hold query).
        seedHeldReservation(userId, OffsetDateTime.now().plusMinutes(9));

        assertThatThrownBy(() -> seedHeldReservation(otherUserId, OffsetDateTime.now().plusMinutes(9)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // -------------------------------------------------------------- helpers

    /** Ages a reservation past its durable expiry — the DB-side half of "the hold elapsed". */
    private void backdateExpiry(UUID reservationId) {
        var managed = reservationRepository.findById(reservationId).orElseThrow();
        managed.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        reservationRepository.save(managed);
    }

    private Reservation seedHeldReservation(UUID ownerId, OffsetDateTime expiresAt) {
        return reservationRepository.save(Reservation.builder()
                .id(UUID.randomUUID())
                .user(userRepository.getReferenceById(ownerId))
                .seat(seatRepository.getReferenceById(seatId))
                .status(ReservationStatus.HELD)
                .expiresAt(expiresAt)
                .build());
    }
}
