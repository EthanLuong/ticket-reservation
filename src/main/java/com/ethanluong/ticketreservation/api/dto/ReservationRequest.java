package com.ethanluong.ticketreservation.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReservationRequest(
        @NotNull UUID seatId
) {
}
