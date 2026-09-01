package com.ethanluong.payment;

import com.ethanluong.payment.entity.Payment;
import com.ethanluong.payment.repository.PaymentProcessedEventRepository;
import com.ethanluong.payment.repository.PaymentRepository;
import com.ethanluong.payment.type.PaymentStatus;

import com.ethanluong.payment.entity.OutboxEntry;
import com.ethanluong.payment.repository.OutboxEntryRepository;
import com.ethanluong.payment.events.ChargeCard;
import com.ethanluong.payment.events.EventEnvelope;
import com.ethanluong.payment.events.EventTypes;
import com.ethanluong.payment.events.KafkaTopics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Payment command handling: dedup-first, both ChargeCard outcomes, and the
 * always-emit RefundConfirmed rule. Real ObjectMapper (payload marshalling is
 * part of the behavior under test), mocked repos/gateway, fixed clock.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-16T12:00:00Z");

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentProcessedEventRepository paymentProcessedEventRepository;
    @Mock private MockPaymentGateway gateway;
    @Mock private OutboxEntryRepository outboxEntryRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PaymentService paymentService;

    private final UUID sagaId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository, paymentProcessedEventRepository,
                gateway, outboxEntryRepository, objectMapper,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    private EventEnvelope envelope(String eventType, Object payload) {
        return new EventEnvelope(UUID.randomUUID(), eventType, 1,
                OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC), sagaId,
                objectMapper.valueToTree(payload));
    }

    private OutboxEntry emittedOutboxRow() {
        ArgumentCaptor<OutboxEntry> captor = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxEntryRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("duplicate eventId → marker found, nothing else happens")
    void duplicateCommand_noOps() {
        EventEnvelope env = envelope(EventTypes.CHARGE_CARD, new ChargeCard(5000));
        when(paymentProcessedEventRepository.existsById(env.eventId())).thenReturn(true);

        paymentService.handleCommand(env);

        verifyNoInteractions(gateway, paymentRepository, outboxEntryRepository);
    }

    @Test
    @DisplayName("ChargeCard approved → AUTHORIZED row + PaymentConfirmed on payment.evt, keyed to the saga")
    void chargeCard_approved() {
        when(gateway.charge(any(), anyLong()))
                .thenReturn(MockPaymentGateway.ChargeResult.approved("mock-" + sagaId));

        paymentService.handleCommand(envelope(EventTypes.CHARGE_CARD, new ChargeCard(5000)));

        ArgumentCaptor<Payment> payment = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(payment.capture());
        assertThat(payment.getValue().getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getValue().getSagaId()).isEqualTo(sagaId);
        assertThat(payment.getValue().getGatewayRef()).isEqualTo("mock-" + sagaId);

        OutboxEntry row = emittedOutboxRow();
        assertThat(row.getTopic()).isEqualTo(KafkaTopics.PAYMENT_EVT);
        assertThat(row.getAggregateType()).isEqualTo("Payment");
        assertThat(row.getAggregateId()).isEqualTo(sagaId);
        assertThat(row.getPayload()).contains(EventTypes.PAYMENT_CONFIRMED).contains(sagaId.toString());
    }

    @Test
    @DisplayName("ChargeCard declined → FAILED row (null gatewayRef) + PaymentFailed emitted")
    void chargeCard_declined() {
        when(gateway.charge(any(), anyLong()))
                .thenReturn(MockPaymentGateway.ChargeResult.declined("Suspicious payment"));

        paymentService.handleCommand(envelope(EventTypes.CHARGE_CARD, new ChargeCard(15000)));

        ArgumentCaptor<Payment> payment = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(payment.capture());
        assertThat(payment.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getValue().getGatewayRef()).isNull();

        assertThat(emittedOutboxRow().getPayload()).contains(EventTypes.PAYMENT_FAILED);
    }

    @Test
    @DisplayName("CancelChargeIfStarted with an AUTHORIZED payment → REFUNDED + RefundConfirmed")
    void cancel_refundsAuthorizedPayment() {
        Payment authorized = Payment.builder().sagaId(sagaId).amountCents(5000)
                .status(PaymentStatus.AUTHORIZED).gatewayRef("mock-" + sagaId).build();
        when(paymentRepository.findBySagaId(sagaId)).thenReturn(Optional.of(authorized));

        paymentService.handleCommand(envelope(EventTypes.CANCEL_CHARGE_IF_STARTED, null));

        assertThat(authorized.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(emittedOutboxRow().getPayload()).contains(EventTypes.REFUND_CONFIRMED);
    }

    @Test
    @DisplayName("CancelChargeIfStarted with no payment → STILL emits RefundConfirmed (compensation settled ≠ money moved)")
    void cancel_noCharge_stillEmits() {
        when(paymentRepository.findBySagaId(sagaId)).thenReturn(Optional.empty());

        paymentService.handleCommand(envelope(EventTypes.CANCEL_CHARGE_IF_STARTED, null));

        // The saga is parked in COMPENSATING — silence here would strand it forever.
        assertThat(emittedOutboxRow().getPayload()).contains(EventTypes.REFUND_CONFIRMED);
        verify(paymentRepository, never()).save(any());
    }
}
