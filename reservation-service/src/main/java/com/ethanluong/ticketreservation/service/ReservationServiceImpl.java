package com.ethanluong.ticketreservation.service;

import com.ethanluong.ticketreservation.api.exception.CancellationWindowClosedException;
import com.ethanluong.ticketreservation.api.exception.ResourceNotFoundException;
import com.ethanluong.ticketreservation.api.exception.SeatContentionException;
import com.ethanluong.ticketreservation.api.exception.SeatNotAvailableException;
import com.ethanluong.ticketreservation.api.exception.SeatOperationException;
import com.ethanluong.ticketreservation.domain.entity.Reservation;
import com.ethanluong.ticketreservation.domain.repository.ReservationRepository;
import com.ethanluong.ticketreservation.domain.repository.SeatRepository;
import com.ethanluong.ticketreservation.domain.repository.UserRepository;
import com.ethanluong.ticketreservation.domain.type.ReservationStatus;
import com.ethanluong.ticketreservation.domain.type.SeatStatus;
import com.ethanluong.ticketreservation.saga.SagaOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;


@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationServiceImpl implements ReservationService {

    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationHoldStore holdStore;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final SagaOrchestrator sagaOrchestrator;

    @Value("${app.reservation.hold-duration-minutes}")
    private long reservationExpiryMinutes;

    @Override
    public Reservation reserve(UUID userId, UUID seatId) {
        return holdStore.withSeatLock(seatId, () -> doReserve(userId, seatId));
    }

    private Reservation doReserve(UUID userId, UUID seatId) {
        UUID reservationId = UUID.randomUUID();
        Duration ttl = Duration.ofMinutes(reservationExpiryMinutes);

        // Redis-first: SET NX EX gives atomic collision detection under the lock.
        if (!holdStore.tryHold(seatId, reservationId, ttl)) {
            throw new SeatNotAvailableException(seatId);
        }

        try {
            return tx.execute(status -> {
                var seat = seatRepository.findById(seatId).orElseThrow();

                if (seat.getStatus() == SeatStatus.SOLD) {
                    throw new SeatNotAvailableException(seatId);
                }

                // I2: Postgres owns hold truth. Our tryHold succeeded, but a missing key
                // is not proof the previous hold elapsed — keys also vanish on Redis
                // restart/failover/eviction. An unexpired HELD row means that hold is
                // still live: refuse to steal it (the catch below releases our key).
                // I4: the sweep of genuinely-expired rows runs unconditionally, not only
                // when seat=HELD, so an orphaned HELD row can't wedge an AVAILABLE seat.
                OffsetDateTime now = OffsetDateTime.now(clock);
                List<Reservation> heldRows =
                        reservationRepository.findAllByStatusAndSeat_Id(ReservationStatus.HELD, seatId);
                if (heldRows.stream().anyMatch(r -> !r.getExpiresAt().isBefore(now))) {
                    throw new SeatNotAvailableException(seatId);
                }
                heldRows.forEach(r -> r.setStatus(ReservationStatus.EXPIRED));

                seat.setStatus(SeatStatus.HELD);
                seatRepository.saveAndFlush(seat);

                Reservation newReservation = Reservation.builder()
                        .id(reservationId)
                        .user(userRepository.getReferenceById(userId))
                        .seat(seat)
                        .status(ReservationStatus.HELD)
                        .expiresAt(OffsetDateTime.now(clock).plusMinutes(reservationExpiryMinutes))
                        .build();


                Reservation reservation = reservationRepository.save(newReservation);
                sagaOrchestrator.start(reservationId, seat.getPriceCents());
                return reservation;
            });
        } catch (RuntimeException e) {
            // @Transactional rolled back the DB, but Redis has no rollback.
            if (!holdStore.release(seatId, reservationId)) {
                // Should be impossible: we SET this key moments ago, inside the seat lock,
                // with a 10-min TTL. A miss here means Redis lost/changed the key underneath us.
                log.warn("reserve compensation: hold for seat {} not owned by reservation {} moments after creation",
                        seatId, reservationId);
            }
            throw e;
        }
    }

    @Override
    public Reservation cancel(UUID userId, UUID reservationId) {
        // Re-fetch seatId outside the lock so we can key the lock on it.
        UUID seatId = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId))
                .getSeat().getId();

        return holdStore.withSeatLock(seatId, () -> doCancel(reservationId, userId));
    }

    private record CancelOutcome(Reservation cancelled, ReservationStatus reservationStatus) {}

    private Reservation doCancel(UUID reservationId, UUID userId) {
        CancelOutcome outcome = tx.execute(status -> {
            var reservation = reservationRepository.findById(reservationId).orElseThrow();
            var seat = reservation.getSeat();
            var reservationStatus = reservation.getStatus();

            if(!reservation.getUser().getId().equals(userId)) {
                throw new ResourceNotFoundException("Reservation", reservationId);
            } else if( reservation.getStatus() == ReservationStatus.CANCELLED || reservation.getStatus() == ReservationStatus.EXPIRED)  {
                throw new SeatOperationException("Reservation has already been cancelled or expired");
            } else if(reservation.getStatus() == ReservationStatus.CONFIRMED && !OffsetDateTime.now(clock).isBefore(seat.getEvent().getStartsAt().minusDays(3))){
                throw new CancellationWindowClosedException("Too late to cancel this seat");
            }

            seat.setStatus(SeatStatus.AVAILABLE);
            reservation.setStatus(ReservationStatus.CANCELLED);

            seatRepository.save(seat);
            return new CancelOutcome(reservationRepository.save(reservation), reservationStatus);
        });
        if (outcome.reservationStatus == ReservationStatus.HELD
                && !holdStore.release(outcome.cancelled.getSeat().getId(), reservationId)) {
            // Expected race: the hold TTL-expired between loading the reservation and releasing.
            log.info("cancel: hold for reservation {} already expired before release", reservationId);
        }

        return outcome.cancelled;
    }

    @Override
    public List<Reservation> myReservations(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User", userId);
        }

        List<Reservation> reservations = tx.execute(status ->
                reservationRepository.findAllByUser_IdOrderByCreatedAtDesc(userId));

        // Lazy reconciliation: flip genuinely-expired HELD rows on read and free
        // their seats — self-healing without a sweeper process.
        // I2: Postgres owns hold truth. A missing Redis key alone is NOT expiry
        // (keys vanish on restart/failover/eviction); only rows past their durable
        // expires_at are candidates. Key-present short-circuits rows that are
        // demonstrably still held.
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Reservation> candidates = reservations.stream()
                .filter(r -> r.getStatus() == ReservationStatus.HELD)
                .filter(r -> r.getExpiresAt().isBefore(now))
                .filter(r -> !holdStore.hasHold(r.getSeat().getId()))
                .toList();

        for (Reservation r : candidates) {
            if (expireStaleReservation(r.getId(), r.getSeat().getId())) {
                // Reflect the flip in the returned list so callers see consistent state.
                r.setStatus(ReservationStatus.EXPIRED);
            }
        }

        return reservations;
    }

    /**
     * C2: double-checked expiry. The staleness decision above was computed from an
     * UNLOCKED snapshot, so every check is repeated inside the seat lock + tx before
     * anything mutates — check-then-act is only safe when the check lives inside the
     * mutual-exclusion + transaction boundary (same pattern as doReserve).
     *
     * @return true if the row was actually flipped to EXPIRED
     */
    private boolean expireStaleReservation(UUID reservationId, UUID seatId) {
        try {
            return Boolean.TRUE.equals(holdStore.withSeatLock(seatId, () -> tx.execute(status -> {
                var managed = reservationRepository.findById(reservationId).orElse(null);
                if (managed == null
                        || managed.getStatus() != ReservationStatus.HELD
                        || !managed.getExpiresAt().isBefore(OffsetDateTime.now(clock))
                        || holdStore.hasHold(seatId)) {
                    return false; // state moved between snapshot and lock — not stale after all
                }
                managed.setStatus(ReservationStatus.EXPIRED);

                // No competing-hold query needed: idx_reservations_active_seat (partial
                // unique on HELD rows) makes a second live hold unrepresentable, and a
                // competitor's reserve runs under this same seat lock and would have
                // expired this row first — the status re-check above already caught that.
                var seat = managed.getSeat();
                if (seat.getStatus() == SeatStatus.HELD) {
                    seat.setStatus(SeatStatus.AVAILABLE);
                }
                return true;
            })));
        } catch (SeatContentionException e) {
            // Reconciliation is opportunistic — a contended seat heals on the next read.
            log.debug("reconciliation skipped for seat {} — lock contended", seatId);
            return false;
        }
    }
}
