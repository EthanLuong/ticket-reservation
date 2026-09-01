package com.ethanluong.ticketreservation.saga;

import com.ethanluong.ticketreservation.logging.Correlation;
import com.ethanluong.ticketreservation.saga.events.EventEnvelope;
import com.ethanluong.ticketreservation.saga.events.KafkaTopics;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final ObjectMapper objectMapper;
    private final SagaOrchestrator sagaOrchestrator;

    @KafkaListener(topics = KafkaTopics.PAYMENT_EVT)
    public void onPaymentEvent(String event, Acknowledgment ack) {
        EventEnvelope eventEnvelope = objectMapper.readValue(event, EventEnvelope.class);

        try(MDC.MDCCloseable mdc = Correlation.saga(eventEnvelope.sagaId())){
            sagaOrchestrator.handlePaymentEvent(eventEnvelope);
            ack.acknowledge();
        }



    }
}
