package com.ethanluong.ticketreservation.saga.events;

/**
 * The topic names — single source of truth for producers, consumers, topic
 * declaration, and outbox rows. Plain String constants (not an enum) because
 * {@code @KafkaListener(topics = ...)} requires compile-time constants.
 */
public final class KafkaTopics {

    /** Commands: reservation-side → payment. Partitioned by sagaId. */
    public static final String PAYMENT_CMD = "payment.cmd";

    /** Replies: payment → reservation-side. Partitioned by sagaId. */
    public static final String PAYMENT_EVT = "payment.evt";

    public static final String PAYMENT_CMD_DLT = PAYMENT_CMD + ".DLT";
    public static final String PAYMENT_EVT_DLT = PAYMENT_EVT + ".DLT";

    private KafkaTopics() {
    }
}
