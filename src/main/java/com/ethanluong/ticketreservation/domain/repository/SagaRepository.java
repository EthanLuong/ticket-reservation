package com.ethanluong.ticketreservation.domain.repository;

import com.ethanluong.ticketreservation.domain.entity.Saga;
import com.ethanluong.ticketreservation.domain.type.SagaState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SagaRepository extends JpaRepository<Saga, UUID> {

    @Query("select s.id from Saga s where s.state = :state and s.createdAt < :expiryCutoff")
    List<UUID> findIdsByStateAndCreatedAtBefore(SagaState state, OffsetDateTime expiryCutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Saga> findWithLockById(UUID id);
}
