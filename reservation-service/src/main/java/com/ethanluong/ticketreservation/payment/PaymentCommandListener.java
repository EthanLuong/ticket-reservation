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

        EventEnvelope eventEnvelope;
        eventEnvelope = objectMapper.readValue(event, EventEnvelope.class);

        paymentService.handleCommand(eventEnvelope);
        ack.acknowledge();
    }
}