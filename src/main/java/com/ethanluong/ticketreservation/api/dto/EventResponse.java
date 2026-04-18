package com.ethanluong.ticketreservation.api.dto;

import com.ethanluong.ticketreservation.domain.entity.Event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EventResponse(
        UUID id,
        String name,
        String description,
        String venue,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt
) {
    public static EventResponse from(Event e) {
        return new EventResponse(e.getId(), e.getName(), e.getDescription(), e.getVenue(), e.getStartsAt(), e.getEndsAt());
    }
}
