package com.ethanluong.ticketreservation.saga;

import com.ethanluong.ticketreservation.domain.entity.OutboxEntry;
import com.ethanluong.ticketreservation.domain.repository.OutboxEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The publish LOOP only — repo-order sends with aggregateId key, mark-after-send
 * from the injected clock, and STOP-on-first-failure (decision #5: continuing past
 * a failed row would reorder same-saga commands). Broker behavior is OutboxPublisherIT's job.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    /** All time is relative to this instant, never the wall clock. */
    private static final Instant FIXED_NOW = Instant.parse("2026-07-16T12:00:00Z");

    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private OutboxEntryRepository outboxEntryRepository;

    private OutboxPublisher publisher;

    private final UUID sagaId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(kafkaTemplate, outboxEntryRepository,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("sends rows in repo order with aggregateId as key; marks each from the injected clock")
    void sendsInOrderAndMarks() {
        OutboxEntry first = entry("p1");
        OutboxEntry second = entry("p2");
        when(outboxEntryRepository.findTop50ByProcessedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(first, second));
        CompletableFuture<SendResult<String, String>> ok = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(ok);

        publisher.publish();

        InOrder order = inOrder(kafkaTemplate);
        order.verify(kafkaTemplate).send("payment.cmd", sagaId.toString(), "p1");
        order.verify(kafkaTemplate).send("payment.cmd", sagaId.toString(), "p2");
        assertThat(first.getProcessedAt())
                .isEqualTo(OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));
        assertThat(second.getProcessedAt()).isNotNull();
        verify(outboxEntryRepository).save(first);
        verify(outboxEntryRepository).save(second);
    }

    @Test
    @DisplayName("failure at row #2: row #1 marked, #2 unmarked, #3 never sent (stop-on-first-failure)")
    void stopsOnFirstFailure() {
        OutboxEntry first = entry("p1");
        OutboxEntry failing = entry("p2");
        OutboxEntry third = entry("p3");
        when(outboxEntryRepository.findTop50ByProcessedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(first, failing, third));
        CompletableFuture<SendResult<String, String>> ok = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(any(), any(), eq("p1"))).thenReturn(ok);
        when(kafkaTemplate.send(any(), any(), eq("p2")))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        publisher.publish();

        verify(outboxEntryRepository).save(first);
        verify(outboxEntryRepository, never()).save(failing);
        verify(outboxEntryRepository, never()).save(third);
        verify(kafkaTemplate, never()).send(any(), any(), eq("p3"));
        assertThat(failing.getProcessedAt()).isNull();
        assertThat(third.getProcessedAt()).isNull();
    }

    @Test
    @DisplayName("empty outbox: no sends")
    void emptyOutboxNoSends() {
        when(outboxEntryRepository.findTop50ByProcessedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of());

        publisher.publish();

        verifyNoInteractions(kafkaTemplate);
    }

    private OutboxEntry entry(String payload) {
        OutboxEntry entry = new OutboxEntry();
        entry.setId(UUID.randomUUID());
        entry.setAggregateType("Saga");
        entry.setAggregateId(sagaId);
        entry.setTopic("payment.cmd");
        entry.setPayload(payload);
        return entry;
    }
}
