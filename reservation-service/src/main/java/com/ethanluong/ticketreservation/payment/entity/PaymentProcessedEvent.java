package com.ethanluong.ticketreservation.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Payment's OWN dedup marker (ratified Q2 — state travels with the 2b split;
 * no consumer column because one table now has exactly one consumer).
 * LLM-BUILT 2026-07-27 (skeleton — mirrors ProcessedEvent, known ground).
 */
@Entity
@Table(name = "payment_processed_events")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "eventId")
public class PaymentProcessedEvent {

    @Id
    @Column(name = "event_id", columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID eventId;

    @Column(name = "processed_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime processedAt;
}
