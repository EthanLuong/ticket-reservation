package com.ethanluong.ticketreservation.saga;

import com.ethanluong.ticketreservation.domain.entity.OutboxEntry;
import com.ethanluong.ticketreservation.domain.entity.ProcessedEvent;
import com.ethanluong.ticketreservation.domain.entity.Reservation;
import com.ethanluong.ticketreservation.domain.entity.Saga;
import com.ethanluong.ticketreservation.domain.repository.OutboxEntryRepository;
import com.ethanluong.ticketreservation.domain.repository.ProcessedEventRepository;
import com.ethanluong.ticketreservation.domain.repository.ReservationRepository;
import com.ethanluong.ticketreservation.domain.repository.SagaRepository;
import com.ethanluong.ticketreservation.domain.type.ReservationStatus;
import com.ethanluong.ticketreservation.domain.type.SagaState;
import com.ethanluong.ticketreservation.saga.events.ChargeCard;
import com.ethanluong.ticketreservation.saga.events.EventEnvelope;
import com.ethanluong.ticketreservation.saga.events.EventTypes;
import com.ethanluong.ticketreservation.saga.events.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.transaction.annotation.Propagation.MANDATORY;
import static org.springframework.transaction.annotation.Propagation.REQUIRED;

@Component
@Slf4j
public class SagaOrchestrator {

    /** processed_events.consumer value — who deduped this event (not part of the PK). */
    private static final String CONSUMER = "reservation-service";

    private final ObjectMapper objectMapper;
    private final SagaRepository sagaRepository;
    private final OutboxEntryRepository outboxEntryRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ReservationRepository reservationRepository;
    private final Clock clock;

    public SagaOrchestrator(ObjectMapper objectMapper, SagaRepository sagaRepository, OutboxEntryRepository outboxEntryRepository, ProcessedEventRepository processedEventRepository, ReservationRepository reservationRepository, Clock clock) {
        this.objectMapper = objectMapper;
        this.sagaRepository = sagaRepository;
        this.outboxEntryRepository = outboxEntryRepository;
        this.processedEventRepository = processedEventRepository;
        this.reservationRepository = reservationRepository;
        this.clock = clock;
    }

    @Transactional(propagation = MANDATORY)
    public void start(UUID reservationId, long amountCents){
        UUID sagaId = UUID.randomUUID();

        Saga saga = new Saga();
        saga.setId(sagaId);
        saga.setType("BOOKING");
        saga.setReservationId(reservationId);
        saga.setState(SagaState.AWAITING_PAYMENT);

        // ADR 0003: the outbox row stores the serialized ENVELOPE, not the bare payload —
        // the eventType discriminator must ride inside the JSON so the (pre-serialized)
        // outbox publisher never needs to know the type.
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                EventTypes.CHARGE_CARD,
                1, OffsetDateTime.now(clock),
                sagaId,
                objectMapper.valueToTree(new ChargeCard(amountCents)));

        OutboxEntry outboxEntry = new OutboxEntry();
        outboxEntry.setAggregateType("Saga");
        outboxEntry.setAggregateId(sagaId);
        outboxEntry.setPayload(objectMapper.writeValueAsString(envelope));
        outboxEntry.setTopic(KafkaTopics.PAYMENT_CMD);

        sagaRepository.save(saga);
        outboxEntryRepository.save(outboxEntry);
    }

    @Transactional(propagation = REQUIRED)
    public void handlePaymentEvent(EventEnvelope envelope){
        if(processedEventRepository.existsById(envelope.eventId())){
            return;
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(envelope.eventId())
                .consumer(CONSUMER)
                .build());

        Optional<Saga> sagaOptional = sagaRepository.findById(envelope.sagaId());
        if(sagaOptional.isEmpty()){
            log.warn("payment event {}: unknown saga {} — skipped", envelope.eventId(), envelope.sagaId());
            return;
        }
        Saga currentSaga = sagaOptional.get();
        Reservation reservation = reservationRepository.getReferenceById(currentSaga.getReservationId());

        // Each event type knows its own legal from-state (ADR 0003: wrong-state → log + skip;
        // the marker above is already saved, so the skip is permanent on redelivery).
        switch (envelope.eventType()) {
            case EventTypes.PAYMENT_CONFIRMED -> {
                if (notInState(currentSaga, SagaState.AWAITING_PAYMENT, envelope)) return;
                currentSaga.setState(SagaState.COMPLETED);
                reservation.setStatus(ReservationStatus.CONFIRMED);
            }
            case EventTypes.PAYMENT_FAILED -> {
                if (notInState(currentSaga, SagaState.AWAITING_PAYMENT, envelope)) return;
                // Collapsed PAYMENT_FAILED → COMPENSATING → CANCELLED (one tx, same move as
                // start() collapsing PENDING). Trade-off: final state alone no longer says
                // failed-vs-timed-out; if audit needs that, add a reason column.
                currentSaga.setState(SagaState.CANCELLED);
                reservation.setStatus(ReservationStatus.CANCELLED);
                // TODO: release Redis hold + free seat here after m0-audit-fixes merges
                //  (needs owner-aware release(seatId, reservationId)). Until then the 10-min
                //  hold TTL + lazy reconciliation free the seat — slow but safe.
            }
            case EventTypes.REFUND_CONFIRMED -> {
                if (notInState(currentSaga, SagaState.COMPENSATING, envelope)) return;
                // Reservation was already CANCELLED when compensation started (timeout path);
                // the refund confirmation just closes the saga.
                currentSaga.setState(SagaState.CANCELLED);
            }
            default -> log.warn("payment event {}: unknown eventType {} — skipped", envelope.eventId(), envelope.eventType());
        }
    }

    /** Wrong-state guard: at-least-once delivery makes stale/out-of-order events normal — log, don't throw. */
    private boolean notInState(Saga saga, SagaState expected, EventEnvelope envelope) {
        if (saga.getState() == expected) {
            return false;
        }
        log.warn("payment event {} ({}): saga {} in state {}, expected {} — skipped",
                envelope.eventId(), envelope.eventType(), saga.getId(), saga.getState(), expected);
        return true;
    }

}
