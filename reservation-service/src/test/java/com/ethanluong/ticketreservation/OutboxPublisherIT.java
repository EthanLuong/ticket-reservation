package com.ethanluong.ticketreservation;

import com.ethanluong.ticketreservation.domain.entity.OutboxEntry;
import com.ethanluong.ticketreservation.domain.repository.OutboxEntryRepository;
import com.ethanluong.ticketreservation.saga.OutboxPublisher;
import com.ethanluong.ticketreservation.saga.events.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox drain against the real broker: rows publish oldest-first with the
 * aggregateId as key (per-saga partition ordering), get marked processed_at,
 * and a second pass sends nothing. Consumer side mirrors KafkaCanaryIT's
 * capture-listener pattern.
 *
 * created_at is DB-stamped (insertable = false), so row order is forced by
 * JdbcTemplate backdate — same two-clocks trick as SagaTimeoutIT.
 * app.scheduling.enabled=false keeps the background publisher AND sweeper out;
 * the test drives publish() directly.
 */
@SpringBootTest(properties = "app.scheduling.enabled=false")
@Import({TestcontainersConfiguration.class, OutboxPublisherIT.CaptureListenerConfig.class})
class OutboxPublisherIT {

    /** All time is relative to this instant, never the wall clock. */
    private static final Instant FIXED_NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

    /** Overrides the production Clock bean for this test class only. */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CaptureListenerConfig {

        private final BlockingQueue<ConsumerRecord<String, String>> queue = new LinkedBlockingQueue<>();

        @Bean
        BlockingQueue<ConsumerRecord<String, String>> capturedRecords() {
            return queue;
        }

        @KafkaListener(topics = KafkaTopics.PAYMENT_CMD, groupId = "outbox-publisher-it",
                properties = "auto.offset.reset:earliest")
        void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
            queue.add(record);
            ack.acknowledge();
        }
    }

    @Autowired private OutboxPublisher publisher;
    @Autowired private OutboxEntryRepository outboxEntryRepository;
    @Autowired private BlockingQueue<ConsumerRecord<String, String>> capturedRecords;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        outboxEntryRepository.deleteAllInBatch();
        capturedRecords.clear();
    }

    @Test
    @DisplayName("drains oldest-first with sagaId key, marks processed_at; second pass sends nothing")
    void publishesInOrderMarksAndGoesQuiet() throws Exception {
        UUID sagaId = UUID.randomUUID();
        // payload column is Postgres JSON — fixtures must be valid JSON (string scalars keep exact text)
        // insertion order scrambled on purpose — created_at (backdated) must decide, not save order
        OutboxEntry second = seedEntry(sagaId, "\"payload-2\"", NOW.minusMinutes(2));
        OutboxEntry third = seedEntry(sagaId, "\"payload-3\"", NOW.minusMinutes(1));
        OutboxEntry first = seedEntry(sagaId, "\"payload-1\"", NOW.minusMinutes(3));

        publisher.publish();

        for (String expected : new String[] {"\"payload-1\"", "\"payload-2\"", "\"payload-3\""}) {
            ConsumerRecord<String, String> record = capturedRecords.poll(10, TimeUnit.SECONDS);
            assertThat(record).as("record for %s", expected).isNotNull();
            assertThat(record.value()).isEqualTo(expected);
            assertThat(record.key()).isEqualTo(sagaId.toString());
        }
        for (OutboxEntry entry : outboxEntryRepository.findAll()) {
            assertThat(entry.getProcessedAt()).as("processed_at on %s", entry.getPayload()).isNotNull();
        }

        publisher.publish();

        assertThat(capturedRecords.poll(2, TimeUnit.SECONDS))
                .as("second pass must send nothing — everything is marked")
                .isNull();
        assertThat(first.getId()).isNotNull(); // fixture sanity, and keeps `first` meaningfully used
    }

    // -------------------------------------------------------------- helpers

    /** created_at is DB-stamped on insert; backdate right after so ordering is deterministic. */
    private OutboxEntry seedEntry(UUID sagaId, String payload, OffsetDateTime createdAt) {
        OutboxEntry entry = new OutboxEntry();
        entry.setAggregateType("Saga");
        entry.setAggregateId(sagaId);
        entry.setTopic(KafkaTopics.PAYMENT_CMD);
        entry.setPayload(payload);
        entry = outboxEntryRepository.save(entry);
        jdbcTemplate.update("update outbox set created_at = ? where id = ?", createdAt, entry.getId());
        return entry;
    }
}
