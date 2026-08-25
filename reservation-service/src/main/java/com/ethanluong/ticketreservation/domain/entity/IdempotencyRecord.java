package com.ethanluong.ticketreservation.domain.entity;

import com.ethanluong.ticketreservation.domain.type.IdempotencyStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One Idempotency-Key claim for POST /reservations (current-task card, Step 2 Hint 3).
 * The UNIQUE(user_id, endpoint, idempotency_key) constraint IS the concurrency control:
 * the INSERT either wins or throws — same first-writer-wins pattern as ProcessedEvent,
 * lifted to the REST layer. response_body is a raw JSON string (Jackson 3 boundary).
 * LLM-BUILT 2026-08-18 (boilerplate request — the claim/replay semantics are yours).
 */
@Entity
@Table(name = "idempotency_records", uniqueConstraints = @UniqueConstraint(
        name = "uq_idempotency_user_endpoint_key",
        columnNames = {"user_id", "endpoint", "idempotency_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString(of = {"id", "userId", "endpoint", "idempotencyKey", "status"})
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false, length = 100)
    private String endpoint;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    /** SHA-256 of the canonical request body — catches key reuse with a different payload (422). */
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    /** HTTP status of the original response; null until COMPLETED. */
    @Column(name = "response_status")
    private Integer responseStatus;

    /** Serialized original response body (raw JSON string); null until COMPLETED. */
    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;
}
