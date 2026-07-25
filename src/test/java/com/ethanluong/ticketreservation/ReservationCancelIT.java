package com.ethanluong.ticketreservation;

import com.ethanluong.ticketreservation.api.exception.CancellationWindowClosedException;
import com.ethanluong.ticketreservation.api.exception.ResourceNotFoundException;
import com.ethanluong.ticketreservation.api.exception.SeatOperationException;
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
import com.ethanluong.ticketreservation.service.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C1 regression tests: cancel() ownership guard, status guard, and the
 * 3-days-before-start cancellation window for CONFIRMED reservations.
 *
 * STUBS — arrange/act scaffolding is done; every TODO(you) is an assertion
 * (or a throw-expectation) for you to write. Model assertions on RedisTTLHoldIT.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReservationCancelIT {

    /** All time in these tests is relative to this instant, never the wall clock. */
    private static final Instant FIXED_NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

    /** Overrides the production Clock bean for this test class only. */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private ReservationService reservationService;
    @Autowired private UserRepository userRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private StringRedisTemplate redis;

    private UUID ownerId;
    private UUID attackerId;

    @BeforeEach
    void seedUsers() {
        ownerId = saveUser("owner").getId();
        attackerId = saveUser("attacker").getId();
    }

    @AfterEach
    void cleanup() {
        reservationRepository.deleteAllInBatch();
        seatRepository.findAll().forEach(s -> redis.delete(holdKey(s.getId())));
        seatRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("cross-user cancel → 404, and the victim's hold + seat are untouched")
    void cancelByNonOwner_notFound_andStateUntouched() {
        Seat seat = seedSeat(NOW.plusDays(30), SeatStatus.AVAILABLE);
        Reservation victim = reservationService.reserve(ownerId, seat.getId());

        // 404, not 403 — existence of someone else's reservation must not leak
        assertThatThrownBy(() -> reservationService.cancel(attackerId, victim.getId()))
                .isInstanceOf(ResourceNotFoundException.class);

        // the C1 cascade is dead: victim's hold, seat, and reservation are all untouched
        assertThat(redis.hasKey(holdKey(seat.getId()))).isTrue();
        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.HELD);
        assertThat(reservationRepository.findById(victim.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.HELD);
    }

    @Test
    @DisplayName("cancel an already-CANCELLED reservation → state-guard 409")
    void cancelTwice_rejected() {
        Seat seat = seedSeat(NOW.plusDays(30), SeatStatus.AVAILABLE);
        Reservation reservation = reservationService.reserve(ownerId, seat.getId());
        reservationService.cancel(ownerId, reservation.getId());

        assertThatThrownBy(() -> reservationService.cancel(ownerId, reservation.getId()))
                .isInstanceOf(SeatOperationException.class);
    }

    @Test
    @DisplayName("cancel an EXPIRED reservation → state-guard 409")
    void cancelExpired_rejected() {
        Seat seat = seedSeat(NOW.plusDays(30), SeatStatus.AVAILABLE);
        Reservation expired = seedReservation(seat, ownerId, ReservationStatus.EXPIRED);

        assertThatThrownBy(() -> reservationService.cancel(ownerId, expired.getId()))
                .isInstanceOf(SeatOperationException.class);

        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    @DisplayName("CONFIRMED, event 4 days out → cancel succeeds, seat SOLD→AVAILABLE")
    void cancelConfirmed_windowOpen_succeeds() {
        Seat seat = seedSeat(NOW.plusDays(4), SeatStatus.SOLD);
        Reservation confirmed = seedReservation(seat, ownerId, ReservationStatus.CONFIRMED);

        Reservation cancelled = reservationService.cancel(ownerId, confirmed.getId());

        assertThat(cancelled.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    @DisplayName("CONFIRMED at exactly startsAt − 72h → window-closed 409 (strict boundary)")
    void cancelConfirmed_atExactBoundary_rejected() {
        // startsAt derived from the fixed clock so the boundary is exact by construction
        Seat seat = seedSeat(NOW.plusDays(3), SeatStatus.SOLD);
        Reservation confirmed = seedReservation(seat, ownerId, ReservationStatus.CONFIRMED);

        // strict boundary: at exactly startsAt − 72h the window is CLOSED
        assertThatThrownBy(() -> reservationService.cancel(ownerId, confirmed.getId()))
                .isInstanceOf(CancellationWindowClosedException.class);

        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.SOLD);
        assertThat(reservationRepository.findById(confirmed.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("happy path: owner cancels own HELD reservation")
    void cancelHeld_happyPath() {
        Seat seat = seedSeat(NOW.plusDays(30), SeatStatus.AVAILABLE);
        Reservation reservation = reservationService.reserve(ownerId, seat.getId());

        Reservation cancelled = reservationService.cancel(ownerId, reservation.getId());

        assertThat(cancelled.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.AVAILABLE);
        // release() ran because the pre-cancel status was HELD
        assertThat(redis.hasKey(holdKey(seat.getId()))).isFalse();
    }

    // -------------------------------------------------------------- helpers

    private User saveUser(String name) {
        return userRepository.save(User.builder()
                .email(name + "-" + UUID.randomUUID() + "@test.local")
                .passwordHash(passwordEncoder.encode("password12345"))
                .displayName(name)
                .build());
    }

    private Seat seedSeat(OffsetDateTime eventStartsAt, SeatStatus status) {
        Event event = eventRepository.save(Event.builder()
                .name("Cancel Test Event")
                .venue("Test Arena")
                .startsAt(eventStartsAt)
                .endsAt(eventStartsAt.plusHours(2))
                .build());
        return seatRepository.save(Seat.builder()
                .event(event)
                .seatLabel("C-1")
                .priceCents(5000L)
                .status(status)
                .version(0L)
                .build());
    }

    /** Seeds DB state directly — there is no confirm() flow yet (Phase 2+). */
    private Reservation seedReservation(Seat seat, UUID userId, ReservationStatus status) {
        return reservationRepository.save(Reservation.builder()
                .id(UUID.randomUUID())   // Reservation is Persistable<UUID> — app-assigned id
                .user(userRepository.getReferenceById(userId))
                .seat(seat)
                .status(status)
                // expires_at is NOT NULL for every row; non-HELD rows realistically keep the old hold deadline
                .expiresAt(status == ReservationStatus.HELD ? NOW.plusMinutes(10) : NOW.minusMinutes(5))
                .build());
    }

    private String holdKey(UUID seatId) {
        return "hold:seat:" + seatId;
    }
}
