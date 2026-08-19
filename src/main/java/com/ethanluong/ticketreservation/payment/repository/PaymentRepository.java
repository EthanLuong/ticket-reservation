package com.ethanluong.ticketreservation.payment.repository;

import com.ethanluong.ticketreservation.payment.entity.Payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** LLM-BUILT 2026-07-27 (skeleton — known ground). */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** CancelChargeIfStarted's question: "did we charge for this saga?" */
    Optional<Payment> findBySagaId(UUID sagaId);
}
