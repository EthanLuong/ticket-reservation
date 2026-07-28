package com.ethanluong.ticketreservation.saga;

import com.ethanluong.ticketreservation.domain.repository.SagaRepository;
import com.ethanluong.ticketreservation.domain.type.SagaState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The sweep LOOP only — cutoff derivation and failure isolation. The transition
 * itself (locked load, re-check, outbox) is SagaTimeoutIT's job; the @Scheduled
 * trigger is deliberately untested (that would be testing the framework).
 */
@ExtendWith(MockitoExtension.class)
class SagaTimeoutSweeperTest {

    /** All time is relative to this instant, never the wall clock. */
    private static final Instant FIXED_NOW = Instant.parse("2026-07-16T12:00:00Z");

    @Mock private SagaOrchestrator sagaOrchestrator;
    @Mock private SagaRepository sagaRepository;

    private SagaTimeoutSweeper sweeper;

    @BeforeEach
    void setUp() {
        sweeper = new SagaTimeoutSweeper(sagaOrchestrator, sagaRepository,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("cutoff = injected-clock now − PAYMENT_TIMEOUT; empty sweep touches nothing")
    void cutoffDerivedFromInjectedClock() {
        when(sagaRepository.findIdsByStateAndCreatedAtBefore(any(), any())).thenReturn(List.of());

        sweeper.sweep();

        verify(sagaRepository).findIdsByStateAndCreatedAtBefore(
                SagaState.AWAITING_PAYMENT,
                OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).minus(SagaTimeoutSweeper.PAYMENT_TIMEOUT));
        verifyNoInteractions(sagaOrchestrator);
    }

    @Test
    @DisplayName("saga #2 throwing must not abandon saga #3 (catch-log-continue)")
    void oneFailingSagaDoesNotStopTheSweep() {
        UUID first = UUID.randomUUID();
        UUID failing = UUID.randomUUID();
        UUID last = UUID.randomUUID();
        when(sagaRepository.findIdsByStateAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(first, failing, last));
        doThrow(new RuntimeException("boom")).when(sagaOrchestrator).timeoutSaga(failing);

        sweeper.sweep();

        InOrder order = inOrder(sagaOrchestrator);
        order.verify(sagaOrchestrator).timeoutSaga(first);
        order.verify(sagaOrchestrator).timeoutSaga(failing);
        order.verify(sagaOrchestrator).timeoutSaga(last);
    }
}
