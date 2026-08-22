package com.ethanluong.ticketreservation.payment;

import com.ethanluong.ticketreservation.payment.entity.Payment;
import com.ethanluong.ticketreservation.payment.entity.PaymentProcessedEvent;
import com.ethanluong.ticketreservation.payment.repository.PaymentProcessedEventRepository;
import com.ethanluong.ticketreservation.payment.repository.PaymentRepository;
import com.ethanluong.ticketreservation.payment.type.PaymentStatus;

import com.ethanluong.ticketreservation.domain.entity.OutboxEntry;
import com.ethanluong.ticketreservation.domain.repository.OutboxEntryRepository;
import com.ethanluong.ticketreservation.saga.events.ChargeCard;
import com.ethanluong.ticketreservation.saga.events.EventEnvelope;
import com.ethanluong.ticketreservation.saga.events.EventTypes;
import com.ethanluong.ticketreservation.saga.events.KafkaTopics;
import com.ethanluong.ticketreservation.saga.events.PaymentConfirmed;
import com.ethanluong.ticketreservation.saga.events.PaymentFailed;
import com.ethanluong.ticketreservation.saga.events.RefundConfirmed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Payment-side handler for {@code payment.cmd} — the mirror image of
 * {@code SagaOrchestrator.handlePaymentEvent}, one hop downstream.
 *
 * SKELETON — LLM-BUILT structure 2026-07-27 (dedup frame + emit helper = patterns
 * you've built twice). Every TODO(you) is the card's actual learning: Q3 made real.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentProcessedEventRepository paymentProcessedEventRepository;
    private final MockPaymentGateway gateway;
    private final OutboxEntryRepository outboxEntryRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public void handleCommand(EventEnvelope envelope) {
        // Dedup-first against payment's OWN table (ratified Q2) — same move as the
        // saga listener: marker insert rides this tx, so a redelivered command no-ops.
        if (paymentProcessedEventRepository.existsById(envelope.eventId())) {
            return;
        }
        paymentProcessedEventRepository.save(PaymentProcessedEvent.builder()
                .eventId(envelope.eventId())
                .build());

        switch (envelope.eventType()) {
            case EventTypes.CHARGE_CARD -> handleChargeCard(envelope);
            case EventTypes.CANCEL_CHARGE_IF_STARTED -> handleCancelChargeIfStarted(envelope);
            default -> log.warn("payment cmd {}: unknown eventType {} — skipped",
                    envelope.eventId(), envelope.eventType());
        }
    }

    private void handleChargeCard(EventEnvelope envelope) {
        ChargeCard cmd = objectMapper.treeToValue(envelope.payload(), ChargeCard.class);
        MockPaymentGateway.ChargeResult result = gateway.charge(envelope.sagaId(), cmd.amountCents());

        paymentRepository.save(Payment.builder()
                .sagaId(envelope.sagaId())
                .amountCents(cmd.amountCents())
                .status(result.approved() ? PaymentStatus.AUTHORIZED : PaymentStatus.FAILED)
                .gatewayRef(result.gatewayRef())
                .build());

        if (result.approved()) {
            emitPaymentEvent(EventTypes.PAYMENT_CONFIRMED, envelope.sagaId(), new PaymentConfirmed());
        } else {
            emitPaymentEvent(EventTypes.PAYMENT_FAILED, envelope.sagaId(), new PaymentFailed());
        }
    }

    private void handleCancelChargeIfStarted(EventEnvelope envelope) {
       // No payload to deserialize — CancelChargeIfStarted is an empty record; the
       // envelope's sagaId is enough (which answers the record's own TODO).
       Payment cancelledPayment = paymentRepository.findBySagaId(envelope.sagaId()).orElse(null);
       if (cancelledPayment != null && cancelledPayment.getStatus() == PaymentStatus.AUTHORIZED) {
           cancelledPayment.setStatus(PaymentStatus.REFUNDED);
       }

       // Always emit — the saga parks in COMPENSATING and RefundConfirmed is the only
       // event that moves it to CANCELLED; the event means "compensation settled",
       // not "money moved".
       emitPaymentEvent(EventTypes.REFUND_CONFIRMED, envelope.sagaId(), new RefundConfirmed());
    }

    /**
     * Emit onto {@code payment.evt} via the shared outbox (ratified Q3 — the existing
     * OutboxPublisher drains it; zero publisher changes). LLM-BUILT: this is the exact
     * pattern from {@code SagaOrchestrator.start()}/{@code timeoutSaga()}, third copy.
     */
    private void emitPaymentEvent(String eventType, UUID sagaId, Object payload) {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                eventType,
                1, OffsetDateTime.now(clock), sagaId,
                objectMapper.valueToTree(payload)
        );

        OutboxEntry outboxEntry = new OutboxEntry();
        outboxEntry.setAggregateType("Payment");
        outboxEntry.setAggregateId(sagaId);
        outboxEntry.setPayload(objectMapper.writeValueAsString(envelope));
        outboxEntry.setTopic(KafkaTopics.PAYMENT_EVT);
        outboxEntryRepository.save(outboxEntry);
    }
}
