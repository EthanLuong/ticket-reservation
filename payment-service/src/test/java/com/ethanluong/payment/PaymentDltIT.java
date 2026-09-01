package com.ethanluong.payment;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DLT card IT: proves the DefaultErrorHandler + DeadLetterPublishingRecoverer path
 * end-to-end against the real broker.
 *
 * - A poison pill (unparseable JSON) on payment.cmd must land on payment.cmd.DLT
 *   with the kafka_dlt-* diagnostic headers intact — NOT loop the consumer, NOT
 *   be silently dropped (the old parse-guard stopgap's behavior).
 * - tools.jackson JacksonException is classified not-retryable, so delivery to
 *   the DLT is immediate (no backoff cycles) — the generous await below is for
 *   broker/consumer latency, not retries.
 * - A second poison proves the payment consumer is unwedged and progressing past
 *   bad records, which is the whole point of the card.
 *
 * Happy-path traffic surviving the error handler is covered by PaymentRoundTripIT,
 * which runs in this same suite. (Ported from reservation-service at the R2
 * cutover — the payment.cmd consumer this exercises lives here now.)
 */
@SpringBootTest(properties = {
        // @ServiceConnection supplies the real datasource; this only keeps the
        // ${PAYMENT_DB_PASSWORD} placeholder from failing property binding.
        "spring.datasource.password=overridden-by-service-connection",
})
@Import({TestcontainersConfiguration.class, PaymentDltIT.DltProbeConfig.class})
class PaymentDltIT {

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private BlockingQueue<ConsumerRecord<String, String>> dlt;

    @TestConfiguration(proxyBeanMethods = false)
    static class DltProbeConfig {

        private final BlockingQueue<ConsumerRecord<String, String>> queue = new LinkedBlockingQueue<>();

        @Bean
        BlockingQueue<ConsumerRecord<String, String>> dltRecords() {
            return queue;
        }

        @KafkaListener(topics = "payment.cmd.DLT", groupId = "dlt-probe",
                properties = "auto.offset.reset:earliest")
        void onDltRecord(ConsumerRecord<String, String> record,
                         org.springframework.kafka.support.Acknowledgment ack) {
            queue.add(record);
            ack.acknowledge();
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("poison pill on payment.cmd is dead-lettered with diagnostic headers, and the consumer keeps going")
    void poisonPillIsDeadLettered() throws Exception {
        String poison1 = "{this is not json";
        String poison2 = "also-not-json}}";

        kafkaTemplate.send("payment.cmd", "poison-key-1", poison1).get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, String> first = dlt.poll(30, TimeUnit.SECONDS);
        assertThat(first).as("poison pill should reach payment.cmd.DLT").isNotNull();
        assertThat(first.value()).isEqualTo(poison1);
        // DeadLetterPublishingRecoverer writes the kafka_dlt-* family — the triage story.
        assertThat(header(first, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo("payment.cmd");
        // The listener adapter wraps failures: FQCN = ListenerExecutionFailedException,
        // the real culprit rides in the CAUSE header.
        assertThat(header(first, KafkaHeaders.DLT_EXCEPTION_FQCN)).isNotNull();
        assertThat(header(first, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .as("cause header identifies the Jackson parse failure")
                .contains("tools.jackson");  // e.g. tools.jackson.core.exc.StreamReadException
        assertThat(header(first, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isNotNull();

        // The consumer must be alive and past the first poison: a second bad record
        // also dead-letters instead of the partition being stuck behind the first.
        kafkaTemplate.send("payment.cmd", "poison-key-2", poison2).get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, String> second = dlt.poll(30, TimeUnit.SECONDS);
        assertThat(second).as("consumer should progress past poison and DLT the next one too").isNotNull();
        assertThat(second.value()).isEqualTo(poison2);
    }
}
