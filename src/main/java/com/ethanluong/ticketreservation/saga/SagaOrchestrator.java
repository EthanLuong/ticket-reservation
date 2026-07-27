package com.ethanluong.ticketreservation.saga;

import com.ethanluong.ticketreservation.domain.entity.OutboxEntry;
import com.ethanluong.ticketreservation.domain.entity.Saga;
import com.ethanluong.ticketreservation.domain.repository.OutboxEntryRepository;
import com.ethanluong.ticketreservation.domain.repository.SagaRepository;
import com.ethanluong.ticketreservation.domain.type.SagaState;
import com.ethanluong.ticketreservation.saga.events.ChargeCard;
import com.ethanluong.ticketreservation.saga.events.EventEnvelope;
import com.ethanluong.ticketreservation.saga.events.EventTypes;
import com.ethanluong.ticketreservation.saga.events.KafkaTopics;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.springframework.transaction.annotation.Propagation.MANDATORY;

@Component
public class SagaOrchestrator {

    private final ObjectMapper objectMapper;
    private final SagaRepository sagaRepository;
    private final OutboxEntryRepository outboxEntryRepository;
    private final Clock clock;

    public SagaOrchestrator(ObjectMapper objectMapper, SagaRepository sagaRepository, OutboxEntryRepository outboxEntryRepository,  Clock clock) {
        this.objectMapper = objectMapper;
        this.sagaRepository = sagaRepository;
        this.outboxEntryRepository = outboxEntryRepository;
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

}
