package com.ethanluong.ticketreservation.domain.entity;

import com.ethanluong.ticketreservation.domain.type.SagaState;
import jakarta.persistence.*;
import lombok.*;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persistent state-machine row, one per booking flow (design §3.4/§3.5).
 *
 */
@Entity
@Table(name = "sagas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString(of = {"id", "type", "state"})
public class Saga implements Persistable<UUID> {


    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false, length = 20, updatable = false)
    private String type; // 'BOOKING' — only one saga type in Phase 2

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SagaState state;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    private UUID reservationId;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;

    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = OffsetDateTime.now();
    }

    @Transient
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private boolean isNew = true;

    @Override
    public @Nullable UUID getId() {
        return id;
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

    @Version
    @Column(nullable = false)
    private long version;

}
