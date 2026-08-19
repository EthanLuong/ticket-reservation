package com.ethanluong.ticketreservation.service;

import com.ethanluong.ticketreservation.domain.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * SKELETON — structure only, on purpose (current-task card, Step 2: the transaction
 * semantics are the learning-bearing part; every TODO below maps to a card hint).
 * LLM-BUILT shell 2026-08-18 at Ethan's request; logic is Ethan's.
 *
 * <p>Wraps one idempotent endpoint execution: claim the key, run the real handler,
 * capture the response for replay. The controller stays thin — it computes the
 * request hash, then delegates: {@code idempotency.execute(userId, "POST /reservations",
 * key, hash, () -> doReserve(...))}.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordRepository records;
    // TODO(you): response serialization — this codebase is Jackson 3, raw-String
    //   boundary country: per-class `new ObjectMapper()` (fasterxml), never injected.

    /**
     * At-most-one execution per (userId, endpoint, key); duplicates get the card's
     * contract: in-flight → 409 (+ Retry-After), completed → replay stored status+body
     * verbatim, same key + different requestHash → 422, FAILED → allowed to re-execute.
     */
    public ResponseEntity<String> execute(UUID userId, String endpoint, String idempotencyKey,
                                          String requestHash, Supplier<ResponseEntity<?>> handler) {
        // TODO(you) Step A — claim: INSERT an IN_PROGRESS record.
        //   Card hint: which propagation makes the claim visible to concurrent duplicates
        //   BEFORE the business transaction commits, and why does @Transactional on this
        //   method (default propagation) get that wrong? (Your July suspended-lock lesson,
        //   in reverse.)

        // TODO(you) Step B — lost the race: catch DataIntegrityViolationException, load the
        //   existing record, and branch: IN_PROGRESS → 409 / hash mismatch → 422 /
        //   COMPLETED → replay / FAILED → fall through to Step C. Decide the order these
        //   checks run in and defend it (hash check before or after state check?).

        // TODO(you) Step C — execute: run handler.get() (the real reserve(), its own tx).

        // TODO(you) Step D — capture: on success, serialize the body and mark the record
        //   COMPLETED + responseStatus + responseBody. Same propagation question as Step A —
        //   what happens to this update if a LATER failure unwinds the calling thread?

        // TODO(you) Step E — failure path: on exception, mark FAILED (or delete) so a retry
        //   can execute, then rethrow. Card warning: don't cache 5xx outcomes you WANT
        //   retried; decide what a replayed 4xx business rejection should look like.

        throw new UnsupportedOperationException("card Step 2 — not implemented yet");
    }

    // TODO(you): static sha256(String canonicalBody) helper (or put hashing in the
    //   controller — decide which layer owns "canonical request body" and why).
}
