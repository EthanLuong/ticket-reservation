package com.ethanluong.ticketreservation.logging;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * MDC keys + scoped setters for log correlation (R3 Task 2). Static by design:
 * MDC itself is static, thread-bound state — wrapping it in a bean would add DI
 * ceremony around what stays a hidden global either way. Keys live here as
 * constants because a typo'd key fails silently (lines just miss the field).
 * payment-service carries its own copy with saga() only — no HTTP surface there.
 */
public final class Correlation {

    public static final String SAGA_ID = "sagaId";
    public static final String REQUEST_ID = "requestId";

    private Correlation() {}  // never instantiated — constants + statics only

    /** Establish saga context for one unit of work; use in try-with-resources. */
    public static MDC.MDCCloseable saga(UUID sagaId) {
        return MDC.putCloseable(SAGA_ID, sagaId.toString());
    }

    /** Establish request context for one HTTP request; set by MDCFilter only. */
    public static MDC.MDCCloseable request(String requestId) {
        return MDC.putCloseable(REQUEST_ID, requestId);
    }
}
