package com.ethanluong.payment.repository;

import com.ethanluong.payment.entity.OutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxEntryRepository extends JpaRepository<OutboxEntry, UUID> {

    List<OutboxEntry> findTop50ByProcessedAtIsNullOrderByCreatedAtAsc();
}
