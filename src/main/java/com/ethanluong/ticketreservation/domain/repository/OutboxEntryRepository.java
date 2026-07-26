package com.ethanluong.ticketreservation.domain.repository;

import com.ethanluong.ticketreservation.domain.entity.OutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OutboxEntryRepository extends JpaRepository<OutboxEntry, UUID> {
}
