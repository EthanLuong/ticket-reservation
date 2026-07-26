package com.ethanluong.ticketreservation.domain.repository;

import com.ethanluong.ticketreservation.domain.entity.Saga;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SagaRepository extends JpaRepository<Saga, UUID> {
}
