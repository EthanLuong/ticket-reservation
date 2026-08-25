package com.ethanluong.ticketreservation.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Transactional-outbox row (design §3.5): inserted in the SAME transaction as the
 * domain mutation it announces; drained to Kafka by OutboxPublisher (card 5).
 * {@code processed_at IS NULL} = not yet published — the partial index's hot set.
 *
 * SKELETON — TODO(you) marks the mappings you own.
 */
@Entity
@Table(name = "outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString(of = {"id", "aggregateType", "topic", "processedAt"})
public class OutboxEntry {


    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 20)
    private String aggregateType; // 'Saga' | 'Payment'

    @Column(name = "aggregate_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID aggregateId;

    @Column(nullable = false, updatable = false, length = 100)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    // Nullable by design: NULL = unpublished. Set by OutboxPublisher after the
    // broker acks — the ONLY mutable column on this row.
    @Column(name = "processed_at")
    private OffsetDateTime processedAt;
}
