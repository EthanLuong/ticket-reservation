package com.ethanluong.ticketreservation.api.controller;

import com.ethanluong.ticketreservation.api.dto.ReservationRequest;
import com.ethanluong.ticketreservation.api.dto.ReservationResponse;
import com.ethanluong.ticketreservation.security.ApplicationUserDetails;
import com.ethanluong.ticketreservation.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    public ReservationResponse reserve(@AuthenticationPrincipal ApplicationUserDetails user,
                                       @Valid @RequestBody ReservationRequest req) {
        return ReservationResponse.from(reservationService.reserve(user.getId(), req.seatId()));
    }

    @DeleteMapping("/{id}")
    public ReservationResponse cancel(@AuthenticationPrincipal ApplicationUserDetails user,
                                      @PathVariable UUID id) {
        return ReservationResponse.from(reservationService.cancel(user.getId(), id));
    }

    @GetMapping("/me")
    public List<ReservationResponse> mine(@AuthenticationPrincipal ApplicationUserDetails user) {
        return reservationService.myReservations(user.getId())
                .stream().map(ReservationResponse::from).toList();
    }
}
