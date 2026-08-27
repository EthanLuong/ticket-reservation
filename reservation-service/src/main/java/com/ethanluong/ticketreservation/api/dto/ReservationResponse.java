package com.ethanluong.ticketreservation.api.dto;

import com.ethanluong.ticketreservation.domain.entity.Reservation;
import com.ethanluong.ticketreservation.domain.type.ReservationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReservationResponse(
        UUID id,
        UUID seatId,
        UUID userId,
        ReservationStatus status,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt
) {
    public static ReservationResponse from(Reservation r) {
        return new ReservationResponse(
                r.getId(),
                r.getSeat().getId(),
                r.getUser().getId(),
                r.getStatus(),
                r.getExpiresAt(),
                r.getCreatedAt()
        );
    }
}
