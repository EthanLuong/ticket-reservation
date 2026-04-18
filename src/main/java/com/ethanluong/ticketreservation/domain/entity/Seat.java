package com.ethanluong.ticketreservation.domain.entity;

import com.ethanluong.ticketreservation.domain.type.SeatStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A Seat is the unit of reservation. Carries a {@code @Version} column for JPA
 * optimistic locking — any reservation write that loses the race will fail with
 * {@code OptimisticLockingFailureException}.
 */
@Entity
@Table(name = "seats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString(of = {"id", "seatLabel", "status", "version"})
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "seat_label", nullable = false, length = 50)
    private String seatLabel;

    @Column(name = "price_cents", nullable = false)
    private Long priceCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;
}
