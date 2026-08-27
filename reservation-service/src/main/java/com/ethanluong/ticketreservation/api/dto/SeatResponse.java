package com.ethanluong.ticketreservation.api.dto;

import com.ethanluong.ticketreservation.domain.entity.Seat;
import com.ethanluong.ticketreservation.domain.type.SeatStatus;

import java.util.UUID;

public record SeatResponse(
        UUID id,
        UUID eventId,
        String seatLabel,
        long priceCents,
        SeatStatus status
) {
    public static SeatResponse from(Seat s) {
        return new SeatResponse(s.getId(), s.getEvent().getId(), s.getSeatLabel(), s.getPriceCents(), s.getStatus());
    }
}
