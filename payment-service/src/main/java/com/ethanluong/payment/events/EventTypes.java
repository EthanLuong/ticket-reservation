package com.ethanluong.payment.events;

/**
 * The {@code eventType} discriminator strings (ADR 0003). Wire contract — never
 * change an existing value once messages carrying it exist; add new constants
 * instead. Plain String constants for the same reason as {@link KafkaTopics}:
 * usable in {@code switch} cases and annotations at compile time.
 */
public final class EventTypes {

    // payment.cmd (orchestrator → payment)
    public static final String CHARGE_CARD = "ChargeCard";
    public static final String CANCEL_CHARGE_IF_STARTED = "CancelChargeIfStarted";

    // payment.evt (payment → orchestrator)
    public static final String PAYMENT_CONFIRMED = "PaymentConfirmed";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String REFUND_CONFIRMED = "RefundConfirmed";

    private EventTypes() {
    }
}
