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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;


@Service
@RequiredArgsConstructor
public class ReservationServiceImpl implements ReservationService {

    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final ReservationRepository reservationRepository;

    @Value("${app.reservation.hold-duration-minutes}")
    private long reservationExpiryMinutes;

    @Override
    @Transactional
    public Reservation reserve(UUID userId, UUID seatId) {
        var seat = seatRepository.findById(seatId).orElseThrow();
        if(seat.getStatus() != SeatStatus.AVAILABLE){
            throw new SeatNotAvailableException(seatId);
        }

        seat.setStatus(SeatStatus.HELD);
        seatRepository.saveAndFlush(seat);

        Reservation newReservation = Reservation.builder()
                .user(userRepository.getReferenceById(userId))
                .seat(seat)
                .status(ReservationStatus.HELD)
                .expiresAt(OffsetDateTime.now().plusMinutes(reservationExpiryMinutes))
                .build();

        return reservationRepository.save(newReservation);

    }

    @Override
    @Transactional
    public Reservation cancel(UUID userId, UUID reservationId) {
        var reservation = reservationRepository.findById(reservationId).orElseThrow();
        var seat = reservation.getSeat();

        seat.setStatus(SeatStatus.AVAILABLE);
        reservation.setStatus(ReservationStatus.CANCELLED);

        seatRepository.save(seat);
        return reservationRepository.save(reservation);
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
