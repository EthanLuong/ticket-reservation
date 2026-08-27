package com.ethanluong.ticketreservation.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Consumer-side idempotency ledger (design §2.3/§3.5): PRIMARY KEY on event_id
 * makes dedup a constraint, not a check-then-act — inserting a duplicate FAILS
 * atomically, in the same transaction as the side effects it guards.
 *
 * SKELETON — one TODO, but it's the one that matters.
 */
@Entity
@Table(name = "processed_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "eventId")
@ToString(of = {"eventId", "consumer"})
public class ProcessedEvent implements Persistable<UUID> {

    @Id
    @Column(name = "event_id", columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID eventId;

    @Column(nullable = false, updatable = false, length = 50)
    private String consumer;

    @Column(name = "processed_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime processedAt;

    @Transient
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private boolean isNew = true;

    @Override
    public @Nullable UUID getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
