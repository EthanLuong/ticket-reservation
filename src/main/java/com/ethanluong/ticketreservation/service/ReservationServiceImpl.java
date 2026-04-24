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
        // Pre-generate the reservation id so it can be written to Redis
        // before the DB row exists. Hibernate's UUID strategy honors a
        // pre-set id (only generates when null).
        UUID reservationId = UUID.randomUUID();
        Duration ttl = Duration.ofMinutes(reservationExpiryMinutes);

        // Redis-first: SET NX EX is an atomic collision check. Only the
        // first caller for this seat wins; the rest return false and fail fast.
        if (!holdStore.tryHold(seatId, reservationId, ttl)) {
            throw new SeatNotAvailableException(seatId);
        }

        // @Transactional does not cover Redis. Drive the DB boundary
        // programmatically so a failed transaction can compensate the
        // Redis write explicitly in the catch block.
        try {
            return tx.execute(status -> {
                var seat = seatRepository.findById(seatId).orElseThrow();
                if (seat.getStatus() != SeatStatus.AVAILABLE) {
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

                // save() works with a pre-set id because Reservation implements
                // Persistable — its isNew() returns true until the @PostPersist
                // callback flips the flag, so Spring Data routes to persist, not merge.
                return reservationRepository.save(newReservation);
            });
        } catch (RuntimeException e) {
            holdStore.release(seatId);
            throw e;
        }
    }

    @Override
    public Reservation cancel(UUID userId, UUID reservationId) {
        Reservation cancelled = tx.execute(status -> {
            var reservation = reservationRepository.findById(reservationId).orElseThrow();
            var seat = reservation.getSeat();

            seat.setStatus(SeatStatus.AVAILABLE);
            reservation.setStatus(ReservationStatus.CANCELLED);

            seatRepository.save(seat);
            return reservationRepository.save(reservation);
        });

        // Release only after the DB commit succeeds. If the tx rolled back,
        // the key stays and self-heals at TTL; a premature release would
        // free the seat for someone else before the cancel is durable.
        holdStore.release(cancelled.getSeat().getId());
        return cancelled;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Reservation> myReservations(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User", userId);
        }
        return reservationRepository.findAllByUser_IdOrderByCreatedAtDesc(userId);
    }

    @Override
    @Transactional
    public int sweepExpired() {
        var expired = reservationRepository.findAllByStatusAndExpiresAtBefore(
                ReservationStatus.HELD, OffsetDateTime.now());

        for (var reservation : expired) {
            reservation.setStatus(ReservationStatus.EXPIRED);
            reservation.getSeat().setStatus(SeatStatus.AVAILABLE);
        }

        return expired.size();
    }
}
