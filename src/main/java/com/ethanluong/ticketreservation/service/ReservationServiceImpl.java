package com.ethanluong.ticketreservation.service;

import com.ethanluong.ticketreservation.api.exception.ResourceNotFoundException;
import com.ethanluong.ticketreservation.domain.entity.Reservation;
import com.ethanluong.ticketreservation.domain.repository.ReservationRepository;
import com.ethanluong.ticketreservation.domain.repository.SeatRepository;
import com.ethanluong.ticketreservation.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * ============================================================================
 *  TODO(ethan) — Phase 0 interview story lives here. Do not let Claude do it.
 * ============================================================================
 *
 * {@link #reserve(UUID, UUID)} must implement the optimistic-locking race:
 *   1. Load the {@code Seat} by id.
 *   2. Verify {@code status == AVAILABLE}; else throw {@code SeatNotAvailableException}.
 *   3. Mutate: set status to {@code HELD}, {@code @Version} will auto-bump on flush.
 *   4. Persist a {@code Reservation} row with {@code status=HELD} and
 *      {@code expiresAt = now + app.reservation.hold-duration-minutes}.
 *   5. Rely on {@code @Transactional} + {@code @Version} to make losers fail
 *      with {@code OptimisticLockingFailureException} — the
 *      {@code GlobalExceptionHandler} already maps that to HTTP 409.
 *
 * The write test you owe yourself:
 *   {@code SeatReservationConcurrencyIT} — spawn 10 threads racing for the same
 *   seat. Assert exactly 1 succeeds; 9 receive 409. This is the interview test.
 *
 * Do NOT add Redis/Redisson locking here. That's Phase 1. Show the DB-layer
 * solution first; the migration story to distributed locking is its own talking
 * point.
 */
@Service
@RequiredArgsConstructor
public class ReservationServiceImpl implements ReservationService {

    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final ReservationRepository reservationRepository;

    @Override
    @Transactional
    public Reservation reserve(UUID userId, UUID seatId) {
        throw new UnsupportedOperationException(
                "TODO(ethan): implement optimistic-lock reservation. See class javadoc.");
    }

    @Override
    @Transactional
    public Reservation cancel(UUID userId, UUID reservationId) {
        throw new UnsupportedOperationException(
                "TODO(ethan): implement cancel — flip reservation to CANCELLED, seat back to AVAILABLE.");
    }

    @Override
    @Transactional(readOnly = true)
    public List<Reservation> myReservations(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User", userId);
        }
        return reservationRepository.findAllByUser_IdOrderByCreatedAtDesc(userId);
    }
}
