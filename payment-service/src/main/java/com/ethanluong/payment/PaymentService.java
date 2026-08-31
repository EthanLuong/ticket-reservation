package com.ethanluong.payment;

import com.ethanluong.payment.entity.Payment;
import com.ethanluong.payment.entity.PaymentProcessedEvent;
import com.ethanluong.payment.repository.PaymentProcessedEventRepository;
import com.ethanluong.payment.repository.PaymentRepository;
import com.ethanluong.payment.type.PaymentStatus;

import com.ethanluong.payment.entity.OutboxEntry;
import com.ethanluong.payment.repository.OutboxEntryRepository;
import com.ethanluong.payment.events.ChargeCard;
import com.ethanluong.payment.events.EventEnvelope;
import com.ethanluong.payment.events.EventTypes;
import com.ethanluong.payment.events.KafkaTopics;
import com.ethanluong.payment.events.PaymentConfirmed;
import com.ethanluong.payment.events.PaymentFailed;
import com.ethanluong.payment.events.RefundConfirmed;
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
     * Emit onto {@code payment.evt} via this service's OWN outbox — R2 replaced the
     * Phase 2a shared-outbox arrangement (old Q3) with a per-service copy: the row
     * rides the same transaction as the payment write, in the payments DB, and this
     * service's own OutboxPublisher drains it.
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
