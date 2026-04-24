package com.ethanluong.ticketreservation.domain.entity;

import com.ethanluong.ticketreservation.domain.type.ReservationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "reservations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString(of = {"id", "status", "expiresAt"})
public class Reservation implements Persistable<UUID> {

    // No @GeneratedValue — reservation id is pre-assigned by the service
    // before the Redis hold is written, so Hibernate must NOT try to generate
    // one at persist time (it would classify any non-null id as DETACHED and
    // refuse the persist).
    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;

    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = OffsetDateTime.now();
    }

    // Persistable<UUID> override — Spring Data JPA's default isNew() is
    // "id == null," which is wrong when the service pre-generates UUIDs
    // (see ReservationServiceImpl#reserve — the id is written to Redis
    // before the DB insert). The @Transient flag starts true, flips false
    // after the entity is persisted or loaded from DB.
    @Transient
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private boolean isNew = true;

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
