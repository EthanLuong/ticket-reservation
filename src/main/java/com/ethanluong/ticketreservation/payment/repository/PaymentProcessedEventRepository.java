package com.ethanluong.ticketreservation.payment.repository;

import com.ethanluong.ticketreservation.payment.entity.PaymentProcessedEvent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** LLM-BUILT 2026-07-27 (skeleton — known ground). */
public interface PaymentProcessedEventRepository extends JpaRepository<PaymentProcessedEvent, UUID> {
}
