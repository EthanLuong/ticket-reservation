package com.ethanluong.payment;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stand-in for a real PSP (Stripe et al). Deterministic rule (Ethan, 2026-07-27):
 * amounts STRICTLY greater than 10_000 cents decline with "Suspicious payment" —
 * so exactly $100.00 approves. Approvals carry {@code gatewayRef = "mock-" + sagaId}.
 * Demo tripwire: the seeded VIP-1 seat ($150.00) declines; every $50 seat approves.
 * No randomness on purpose — tests and demos pick the outcome by picking the amount.
 */
@Component
public class MockPaymentGateway {

    /** What a real gateway returns: approved/declined + its own reference id. */
    public record ChargeResult(boolean approved, String gatewayRef, String declineReason) {

        public static ChargeResult approved(String gatewayRef) {
            return new ChargeResult(true, gatewayRef, null);
        }

        public static ChargeResult declined(String reason) {
            return new ChargeResult(false, null, reason);
        }
    }

    public ChargeResult charge(UUID sagaId, long amountCents) {
        return amountCents > 10_000 ? ChargeResult.declined("Suspicious payment") : ChargeResult.approved("mock-" + sagaId);
    }

    // Note: no refund(...) method on purpose. The mock has no money to move — a refund
    // is just the Payment row flipping to REFUNDED in PaymentService. A real PSP adapter
    // would grow one; say that in the interview instead of building it.
}
