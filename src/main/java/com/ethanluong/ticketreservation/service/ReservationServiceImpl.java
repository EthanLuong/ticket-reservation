package com.ethanluong.ticketreservation.service;

import com.ethanluong.ticketreservation.api.exception.ResourceNotFoundException;
import com.ethanluong.ticketreservation.api.exception.SeatNotAvailableException;
import com.ethanluong.ticketreservation.domain.entity.Reservation;
import com.ethanluong.ticketreservation.domain.repository.ReservationRepository;
import com.ethanluong.ticketreservation.domain.repository.SeatRepository;
import com.ethanluong.ticketreservation.domain.repository.UserRepository;
import com.ethanluong.ticketreservation.domain.type.ReservationStatus;
import com.ethanluong.ticketreservation.domain.type.SeatStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;


@Service
@RequiredArgsConstructor
public class ReservationServiceImpl implements ReservationService {

    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationHoldStore holdStore;
    private final TransactionTemplate tx;

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

                // With both the Redisson lock AND a fresh Redis TTL hold, any
                // HELD row in Postgres for this seat is definitionally stale —
                // the previous hold's Redis TTL has already expired. Reconcile
                // on the spot so the seat becomes reservable without waiting
                // for myReservations() to be called by the prior holder.
                if (seat.getStatus() == SeatStatus.HELD) {
                    reservationRepository
                            .findAllByStatusAndSeat_Id(ReservationStatus.HELD, seatId)
                            .forEach(r -> r.setStatus(ReservationStatus.EXPIRED));
                } else if (seat.getStatus() != SeatStatus.AVAILABLE) {
                    // SOLD (or any future non-AVAILABLE terminal state) is not
                    // reconcilable — truly unavailable.
                    throw new SeatNotAvailableException(seatId);
                }

                seat.setStatus(SeatStatus.HELD);
                seatRepository.saveAndFlush(seat);

                Reservation newReservation = Reservation.builder()
                        .id(reservationId)
                        .user(userRepository.getReferenceById(userId))
                        .seat(seat)
                        .status(ReservationStatus.HELD)
                        .expiresAt(OffsetDateTime.now().plusMinutes(reservationExpiryMinutes))
                        .build();

                return reservationRepository.save(newReservation);
            });
        } catch (RuntimeException e) {
            // @Transactional rolled back the DB, but Redis has no rollback.
            // Compensate explicitly so the hold key doesn't block the seat
            // for up to 10 min.
            holdStore.release(seatId);
            throw e;
        }
    }

    @Override
    public Reservation cancel(UUID userId, UUID reservationId) {
        // Re-fetch seatId outside the lock so we can key the lock on it.
        UUID seatId = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId))
                .getSeat().getId();

        return holdStore.withSeatLock(seatId, () -> doCancel(reservationId));
    }

    private Reservation doCancel(UUID reservationId) {
        Reservation cancelled = tx.execute(status -> {
            var reservation = reservationRepository.findById(reservationId).orElseThrow();
            var seat = reservation.getSeat();

            seat.setStatus(SeatStatus.AVAILABLE);
            reservation.setStatus(ReservationStatus.CANCELLED);

            seatRepository.save(seat);
            return reservationRepository.save(reservation);
        });

        holdStore.release(cancelled.getSeat().getId());
        return cancelled;
    }

    @Override
    public List<Reservation> myReservations(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User", userId);
        }

        List<Reservation> reservations = tx.execute(status ->
                reservationRepository.findAllByUser_IdOrderByCreatedAtDesc(userId));

        // Lazy reconciliation: a HELD row whose Redis TTL hold has expired is
        // stale. Flip to EXPIRED on read, free its seat. Self-healing without
        // a sweeper process.
        List<Reservation> stale = reservations.stream()
                .filter(r -> r.getStatus() == ReservationStatus.HELD)
                .filter(r -> !holdStore.hasHold(r.getSeat().getId()))
                .toList();

        if (!stale.isEmpty()) {
            tx.executeWithoutResult(status -> {
                for (Reservation r : stale) {
                    var managed = reservationRepository.findById(r.getId()).orElseThrow();
                    var seat = managed.getSeat();
                    managed.setStatus(ReservationStatus.EXPIRED);
                    if (seat.getStatus() == SeatStatus.HELD) {
                        seat.setStatus(SeatStatus.AVAILABLE);
                    }
                }
            });
            // Reflect the flip in the returned list so callers see consistent state.
            stale.forEach(r -> r.setStatus(ReservationStatus.EXPIRED));
        }

        return reservations;
    }
}
