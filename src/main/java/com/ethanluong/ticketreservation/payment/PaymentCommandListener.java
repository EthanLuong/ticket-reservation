package com.ethanluong.ticketreservation.payment;

import com.ethanluong.ticketreservation.saga.events.EventEnvelope;
import com.ethanluong.ticketreservation.saga.events.KafkaTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;


@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentCommandListener {

    private final ObjectMapper objectMapper;
    private final PaymentService paymentService;

    @KafkaListener(topics = KafkaTopics.PAYMENT_CMD, groupId = "payment-service")
    public void onPaymentEvent(String event, Acknowledgment ack) {

        // LLM-added 2026-07-27, poison-pill stopgap: an unparseable message would
        // otherwise redeliver forever (nothing was processed, so acking loses nothing).
        // Loud log + ack until the DLT card replaces this with DefaultErrorHandler → payment.cmd.DLT.
        EventEnvelope eventEnvelope;
        try {
            eventEnvelope = objectMapper.readValue(event, EventEnvelope.class);
        } catch (JacksonException e) {
            log.error("payment.cmd: unparseable message — acked and dropped (DLT card will fix)", e);
            ack.acknowledge();
            return;
        }

        paymentService.handleCommand(eventEnvelope);
        ack.acknowledge();
    }
}