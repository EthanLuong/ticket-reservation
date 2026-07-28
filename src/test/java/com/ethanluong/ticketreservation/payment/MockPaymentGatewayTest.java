package com.ethanluong.ticketreservation.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the documented contract, especially the boundary: exactly $100.00 approves. */
class MockPaymentGatewayTest {

    private final MockPaymentGateway gateway = new MockPaymentGateway();
    private final UUID sagaId = UUID.randomUUID();

    @Test
    @DisplayName("exactly 10_000 cents approves (strict >), with mock-<sagaId> ref")
    void boundaryAmount_approves() {
        MockPaymentGateway.ChargeResult result = gateway.charge(sagaId, 10_000);

        assertThat(result.approved()).isTrue();
        assertThat(result.gatewayRef()).isEqualTo("mock-" + sagaId);
        assertThat(result.declineReason()).isNull();
    }

    @Test
    @DisplayName("10_001 cents declines with a reason and no ref")
    void overLimit_declines() {
        MockPaymentGateway.ChargeResult result = gateway.charge(sagaId, 10_001);

        assertThat(result.approved()).isFalse();
        assertThat(result.gatewayRef()).isNull();
        assertThat(result.declineReason()).isEqualTo("Suspicious payment");
    }
}
