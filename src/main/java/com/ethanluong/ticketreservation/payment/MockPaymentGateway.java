package com.ethanluong.ticketreservation.payment;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stand-in for a real PSP (Stripe et al). SKELETON — the decision rule is yours.
 * LLM-BUILT structure 2026-07-27; TODO(you) marks the part you own.
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
        // TODO(you): a DETERMINISTIC rule, not randomness — tests (and demos) must be able
        //  to force both outcomes by choosing the amount. Convention to consider: approve
        //  everything except a magic threshold (e.g. amounts >= $100.00 decline with
        //  "insufficient_funds"), gatewayRef = "mock-" + sagaId. Whatever rule you pick,
        //  write it in the class javadoc — it becomes part of the demo script.
        throw new UnsupportedOperationException("TODO(you): deterministic charge rule");
    }

    // Note: no refund(...) method on purpose. The mock has no money to move — a refund
    // is just the Payment row flipping to REFUNDED in PaymentService. A real PSP adapter
    // would grow one; say that in the interview instead of building it.
}
