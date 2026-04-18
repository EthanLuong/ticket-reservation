package com.ethanluong.ticketreservation.api.controller;

import com.ethanluong.ticketreservation.api.dto.SeatResponse;
import com.ethanluong.ticketreservation.domain.repository.SeatRepository;
import com.ethanluong.ticketreservation.domain.type.SeatStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/seats")
@RequiredArgsConstructor
public class SeatController {

    private final SeatRepository seatRepository;

    @GetMapping
    public List<SeatResponse> byEvent(@RequestParam UUID eventId,
                                      @RequestParam(required = false) SeatStatus status) {
        var seats = (status == null)
                ? seatRepository.findAllByEvent_IdOrderBySeatLabelAsc(eventId)
                : seatRepository.findAllByEvent_IdAndStatusOrderBySeatLabelAsc(eventId, status);
        return seats.stream().map(SeatResponse::from).toList();
    }
}
