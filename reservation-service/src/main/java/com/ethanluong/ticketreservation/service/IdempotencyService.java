package com.ethanluong.ticketreservation.service;

import com.ethanluong.ticketreservation.domain.entity.IdempotencyRecord;
import com.ethanluong.ticketreservation.domain.repository.IdempotencyRecordRepository;
import com.ethanluong.ticketreservation.domain.type.IdempotencyStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

import static com.ethanluong.ticketreservation.domain.type.IdempotencyStatus.COMPLETED;
import static com.ethanluong.ticketreservation.domain.type.IdempotencyStatus.FAILED;
import static com.ethanluong.ticketreservation.domain.type.IdempotencyStatus.IN_PROGRESS;

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
    private final TransactionTemplate transactionTemplate;
    // Injected tools.jackson ObjectMapper — same convention as SagaOrchestrator/PaymentService
    // (the old skeleton TODO claimed per-class fasterxml mappers; the codebase says otherwise).
    private final ObjectMapper objectMapper;

    /**
     * At-most-one execution per (userId, endpoint, key); duplicates get the card's
     * contract: in-flight → 409 (+ Retry-After), completed → replay stored status+body
     * verbatim, same key + different requestHash → 422, FAILED → allowed to re-execute.
     */
    public ResponseEntity<String> execute(UUID userId, String endpoint, String idempotencyKey,
                                          String requestHash, Supplier<ResponseEntity<?>> handler) {
        IdempotencyRecord returnRecord = null;

        try{
             returnRecord = transactionTemplate.execute(status -> {
                var record = IdempotencyRecord.builder()
                        .userId(userId)
                        .endpoint(endpoint)
                        .idempotencyKey(idempotencyKey)
                        .requestHash(requestHash)
                        .status(IN_PROGRESS).build();

                records.save(record);
                records.flush();
                return record;
            });
        } catch (DataIntegrityViolationException e) {
            var existingRecord = records.findByUserIdAndEndpointAndIdempotencyKey(userId, endpoint, idempotencyKey).orElseThrow();
            var recordStatus =  existingRecord.getStatus();

            if(!existingRecord.getRequestHash().equals(requestHash)){
                return ResponseEntity.status(422).build();
            }

            switch (recordStatus){
                case IN_PROGRESS -> {return  ResponseEntity.status(409).header(HttpHeaders.RETRY_AFTER, "2").build();}
                case COMPLETED -> {return ResponseEntity
                                .status(existingRecord.getResponseStatus())
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(existingRecord.getResponseBody());}
                case FAILED -> {
                    if (records.compareAndSetStatus(existingRecord.getId(), FAILED, IN_PROGRESS) == 0) {
                        return ResponseEntity.status(409).header(HttpHeaders.RETRY_AFTER, "2").build();
                    }
                    returnRecord = existingRecord;
                }
            }
        }

        ResponseEntity<?> response;
        try{
            response = handler.get();
        } catch (Exception e){
            returnRecord.setStatus(FAILED);
            records.save(returnRecord);
            throw(e);
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(response.getBody());
        } catch (RuntimeException e) {
            returnRecord.setStatus(FAILED);
            records.save(returnRecord);
            throw e;
        }

        returnRecord.setStatus(COMPLETED);
        returnRecord.setResponseStatus(response.getStatusCode().value());
        returnRecord.setResponseBody(body);
        records.save(returnRecord);

        return ResponseEntity.status(response.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * Canonical-body hash for the claim (Claude, boilerplate by agreement): 64 hex chars,
     * sized to the request_hash column. Lives here, not the controller — callers shouldn't
     * know which algorithm the idempotency layer keys on.
     */
    public static String sha256(String canonicalBody) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalBody.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is JVM-mandated; its absence is a broken runtime", e);
        }
    }
}
