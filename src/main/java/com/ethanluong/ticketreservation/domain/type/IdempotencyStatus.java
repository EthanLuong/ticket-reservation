package com.ethanluong.ticketreservation.domain.type;

/**
 * Lifecycle of one idempotency claim (current-task card, Step 1 Hint 3).
 * IN_PROGRESS = the first request holds the claim and is still executing;
 * COMPLETED = response captured, duplicates replay it;
 * FAILED = execution threw — a retry with the same key MUST be allowed to run.
 * LLM-BUILT 2026-08-18 (boilerplate request — semantics live in IdempotencyService).
 */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
