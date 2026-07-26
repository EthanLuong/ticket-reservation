package com.ethanluong.ticketreservation.saga;

import com.ethanluong.ticketreservation.domain.entity.OutboxEntry;
import com.ethanluong.ticketreservation.domain.entity.Saga;
import com.ethanluong.ticketreservation.domain.repository.OutboxEntryRepository;
import com.ethanluong.ticketreservation.domain.repository.SagaRepository;
import com.ethanluong.ticketreservation.domain.type.SagaState;
import com.ethanluong.ticketreservation.saga.events.ChargeCard;
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

        ChargeCard chargeCard = new ChargeCard(UUID.randomUUID(), sagaId, OffsetDateTime.now(clock), amountCents);

        OutboxEntry outboxEntry = new OutboxEntry();
        outboxEntry.setAggregateType("Saga");
        outboxEntry.setAggregateId(sagaId);
        outboxEntry.setPayload(objectMapper.writeValueAsString(chargeCard));
        outboxEntry.setTopic(KafkaTopics.PAYMENT_CMD);

        sagaRepository.save(saga);
        outboxEntryRepository.save(outboxEntry);


    }

}
