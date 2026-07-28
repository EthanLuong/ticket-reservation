package com.ethanluong.ticketreservation.saga;

import com.ethanluong.ticketreservation.domain.repository.SagaRepository;
import com.ethanluong.ticketreservation.domain.type.SagaState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class SagaTimeoutSweeper {

    // package-private so tests can derive the cutoff from the same constant
    static final Duration PAYMENT_TIMEOUT = Duration.ofSeconds(30);

    private final SagaOrchestrator sagaOrchestrator;
    private final SagaRepository sagaRepository;
    private final Clock clock;

    @Scheduled(fixedDelay = 1000)
    public void sweep() {
        List<UUID> sagaIds = sagaRepository.findIdsByStateAndCreatedAtBefore(
                SagaState.AWAITING_PAYMENT, OffsetDateTime.now(clock).minus(PAYMENT_TIMEOUT));
        for (UUID sagaId : sagaIds) {
            try{
                sagaOrchestrator.timeoutSaga(sagaId);
            } catch(Exception e){
                log.error("timeout sweep failed: Saga [{}]", sagaId, e);
            }
        }
    }
}
