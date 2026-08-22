package com.ethanluong.ticketreservation.api.controller;

import com.ethanluong.ticketreservation.api.dto.ReservationRequest;
import com.ethanluong.ticketreservation.api.dto.ReservationResponse;
import com.ethanluong.ticketreservation.security.ApplicationUserDetails;
import com.ethanluong.ticketreservation.service.IdempotencyService;
import com.ethanluong.ticketreservation.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {



    private final ReservationService reservationService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private static final String ENDPOINT = "POST /api/reservations";

    @PostMapping
    public ResponseEntity<?> reserve(@AuthenticationPrincipal ApplicationUserDetails user,
                                     @RequestHeader(value = "Idempotency-Key") String key,
                                     @Valid @RequestBody ReservationRequest req) {
        String hash = IdempotencyService.sha256(objectMapper.writeValueAsString(req));
        Supplier<ResponseEntity<?>>  supplier = () -> ResponseEntity.status(201).body(ReservationResponse.from(reservationService.reserve(user.getId(), req.seatId())));
        return idempotencyService.execute(user.getId(), ENDPOINT, key, hash, supplier);
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
