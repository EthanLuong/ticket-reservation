package com.ethanluong.payment.logging;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * MDC key + scoped setter for log correlation (R3 Task 2). Static by design:
 * MDC itself is static, thread-bound state — wrapping it in a bean would add DI
 * ceremony around what stays a hidden global either way. Own copy per service
 * (D1 thinking); this one carries saga() only — payment has no HTTP surface,
 * so no requestId.
 */
public final class Correlation {

    public static final String SAGA_ID = "sagaId";

    private Correlation() {}  // never instantiated — constants + statics only

    /** Establish saga context for one unit of work; use in try-with-resources. */
    public static MDC.MDCCloseable saga(UUID sagaId) {
        return MDC.putCloseable(SAGA_ID, sagaId.toString());
    }
}
