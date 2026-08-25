package com.ethanluong.ticketreservation.domain.repository;

import com.ethanluong.ticketreservation.domain.entity.IdempotencyRecord;
import com.ethanluong.ticketreservation.domain.type.IdempotencyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** LLM-BUILT 2026-08-18 (boilerplate request — known ground). */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByUserIdAndEndpointAndIdempotencyKey(
            UUID userId, String endpoint, String idempotencyKey);

    @Transactional
    @Modifying
    @Query("update IdempotencyRecord r set r.status = :to where r.id = :id and r.status = :from")
    int compareAndSetStatus(UUID id, IdempotencyStatus from, IdempotencyStatus to);
}
