package com.ethanluong.ticketreservation.domain.repository;

import com.ethanluong.ticketreservation.domain.entity.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {
    Page<Event> findAllByStartsAtAfterOrderByStartsAtAsc(OffsetDateTime after, Pageable pageable);
}
