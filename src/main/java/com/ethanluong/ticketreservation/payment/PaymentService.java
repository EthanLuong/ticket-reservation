package com.ethanluong.ticketreservation.payment;

import com.ethanluong.ticketreservation.domain.entity.OutboxEntry;
import com.ethanluong.ticketreservation.domain.repository.OutboxEntryRepository;
import com.ethanluong.ticketreservation.saga.events.EventEnvelope;
import com.ethanluong.ticketreservation.saga.events.EventTypes;
import com.ethanluong.ticketreservation.saga.events.KafkaTopics;
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
        // TODO(you): the card's core — Q3 in practice, all inside THIS transaction:
        //  1. ChargeCard cmd = objectMapper.treeToValue(envelope.payload(), ChargeCard.class)
        //  2. MockPaymentGateway.ChargeResult result = gateway.charge(envelope.sagaId(), cmd.amountCents())
        //  3. save a Payment row — status AUTHORIZED or FAILED, gatewayRef/declineReason from result
        //  4. emitPaymentEvent(PAYMENT_CONFIRMED or PAYMENT_FAILED, envelope.sagaId(), payload record)
        //  Payment row + dedup marker + outbox row commit atomically = the payment side's own
        //  dual-write, solved by the same outbox you already built. Keep it to ONE outbox row
        //  per transaction (created_at is tx-scoped — ordering within a tx is undefined; see briefing).
        throw new UnsupportedOperationException("TODO(you): ChargeCard — see comment above");
    }

    private void handleCancelChargeIfStarted(EventEnvelope envelope) {
        // TODO(you): "if you charged, refund; if you didn't, don't — payment decides."
        //  paymentRepository.findBySagaId(envelope.sagaId()):
        //    - AUTHORIZED  → set status REFUNDED, emit REFUND_CONFIRMED
        //    - absent/FAILED → nothing to refund... but the saga is parked in COMPENSATING
        //      waiting for RefundConfirmed either way. So: emit REFUND_CONFIRMED in EVERY
        //      branch — the event means "compensation settled", not "money moved".
        //      Be able to defend that distinction; it's an interview question wearing a TODO.
        throw new UnsupportedOperationException("TODO(you): CancelChargeIfStarted — see comment above");
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
