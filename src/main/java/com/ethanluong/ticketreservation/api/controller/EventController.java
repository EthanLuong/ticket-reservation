package com.ethanluong.ticketreservation.api.controller;

import com.ethanluong.ticketreservation.api.dto.EventResponse;
import com.ethanluong.ticketreservation.api.exception.ResourceNotFoundException;
import com.ethanluong.ticketreservation.domain.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventRepository eventRepository;

    @GetMapping
    public Page<EventResponse> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return eventRepository
                .findAllByStartsAtAfterOrderByStartsAtAsc(OffsetDateTime.now(), PageRequest.of(page, size))
                .map(EventResponse::from);
    }

    @GetMapping("/{id}")
    public EventResponse get(@PathVariable UUID id) {
        return eventRepository.findById(id)
                .map(EventResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Event", id));
    }
}
