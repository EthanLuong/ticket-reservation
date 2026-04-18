package com.ethanluong.ticketreservation;

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
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the TTL sweeper releases only the reservations it should:
 * <ul>
 *   <li>HELD + past-expiry → flipped to EXPIRED, backing seat flipped to AVAILABLE</li>
 *   <li>HELD + future-expiry → untouched</li>
 *   <li>CONFIRMED (any expiry) → untouched (sweeper only targets HELD)</li>
 * </ul>
 *
 * <p>Calls {@code reservationService.sweepExpired()} directly rather than
 * waiting for the {@code @Scheduled} trigger — tests assert on the sweep
 * logic itself, not on Spring's scheduler firing.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReservationSweeperIT {

    @Autowired private ReservationService reservationService;
    @Autowired private UserRepository userRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private UUID userId;
    private UUID eventId;

    @BeforeEach
    void seed() {
        User user = userRepository.save(User.builder()
                .email("sweeper-" + UUID.randomUUID() + "@test.local")
                .passwordHash(passwordEncoder.encode("password12345"))
                .displayName("Sweeper User")
                .build());
        userId = user.getId();

        Event event = eventRepository.save(Event.builder()
                .name("Sweeper Test Event")
                .venue("Test Arena")
                .startsAt(OffsetDateTime.now().plusDays(30))
                .endsAt(OffsetDateTime.now().plusDays(30).plusHours(2))
                .build());
        eventId = event.getId();
    }

    @AfterEach
    void cleanup() {
        reservationRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    /** Creates a HELD seat + HELD reservation for that seat with the given expiry. */
    private Reservation createHeldReservation(String seatLabel, OffsetDateTime expiresAt) {
        Seat seat = seatRepository.save(Seat.builder()
                .event(eventRepository.findById(eventId).orElseThrow())
                .seatLabel(seatLabel)
                .priceCents(5000L)
                .status(SeatStatus.HELD)
                .version(0L)
                .build());

        return reservationRepository.save(Reservation.builder()
                .user(userRepository.findById(userId).orElseThrow())
                .seat(seat)
                .status(ReservationStatus.HELD)
                .expiresAt(expiresAt)
                .build());
    }

    @Test
    @DisplayName("Sweep releases HELD reservations past their expiresAt")
    void expiredHeldReservationIsReleased() {
        Reservation expired = createHeldReservation("A-1", OffsetDateTime.now().minusMinutes(1));
        UUID seatId = expired.getSeat().getId();

        int count = reservationService.sweepExpired();

        assertThat(count).isEqualTo(1);

        var reloaded = reservationRepository.findById(expired.getId()).orElseThrow();
        var seat = seatRepository.findById(seatId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    @DisplayName("Sweep leaves HELD reservations whose TTL has not yet elapsed")
    void futureHeldReservationIsUntouched() {
        Reservation fresh = createHeldReservation("A-2", OffsetDateTime.now().plusMinutes(10));
        UUID seatId = fresh.getSeat().getId();

        int count = reservationService.sweepExpired();

        assertThat(count).isZero();

        var reloaded = reservationRepository.findById(fresh.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(seatRepository.findById(seatId).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.HELD);
    }

    @Test
    @DisplayName("Sweep ignores non-HELD reservations even when past expiresAt")
    void confirmedReservationIsUntouchedRegardlessOfExpiry() {
        Reservation confirmed = createHeldReservation("A-3", OffsetDateTime.now().minusMinutes(1));
        confirmed.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(confirmed);

        int count = reservationService.sweepExpired();

        assertThat(count).isZero();

        var reloaded = reservationRepository.findById(confirmed.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Mixed batch: sweep releases only the expired HELD rows")
    void mixedBatchOnlyExpiredAreSwept() {
        Reservation expired = createHeldReservation("B-1", OffsetDateTime.now().minusMinutes(5));
        Reservation fresh = createHeldReservation("B-2", OffsetDateTime.now().plusMinutes(10));

        int count = reservationService.sweepExpired();

        assertThat(count).isEqualTo(1);

        assertThat(reservationRepository.findById(expired.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservationRepository.findById(fresh.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.HELD);
    }
}
