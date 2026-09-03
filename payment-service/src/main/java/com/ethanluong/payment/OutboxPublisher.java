package com.ethanluong.payment;

import com.ethanluong.payment.entity.OutboxEntry;
import com.ethanluong.payment.logging.Correlation;
import com.ethanluong.payment.repository.OutboxEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxEntryRepository outboxEntryRepository;
    private final Clock clock;

    @Scheduled(fixedDelay = 1000)
    public void publish() {
        // Ordered oldest-first: rows for the same saga MUST publish in creation order
        // (key = aggregateId only guarantees partition order for what we actually send in order).
        List<OutboxEntry> outboxEntries = outboxEntryRepository.findTop50ByProcessedAtIsNullOrderByCreatedAtAsc();

        for (OutboxEntry outboxEntry : outboxEntries) {
            try (MDC.MDCCloseable mdc = Correlation.saga(outboxEntry.getAggregateId())) {
                kafkaTemplate.send(outboxEntry.getTopic(), outboxEntry.getAggregateId().toString(), outboxEntry.getPayload()).get(10, TimeUnit.SECONDS);
                outboxEntry.setProcessedAt(OffsetDateTime.now(clock));
                outboxEntryRepository.save(outboxEntry);
                log.info("outbox row [{}] published to {}", outboxEntry.getId(), outboxEntry.getTopic());
            } catch (Exception e){
                log.error("ABORT publish(): Outbox entry publish failed at entry [{}] topic [{}]", outboxEntry.getId(), outboxEntry.getTopic(), e);
                if (e instanceof InterruptedException) { Thread.currentThread().interrupt() ;}
                return;
            }
        }
    }
}
