package com.ethanluong.ticketreservation.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Backs reservation holds with Redis-native TTL. Collision detection is
 * {@code SET NX EX} — a single atomic round trip. Release is {@code DEL}
 * and is idempotent (safe to call on already-expired or missing keys).
 *
 * <p>Key schema: {@code hold:seat:{seatId}} → {@code reservationId}. Indexed
 * by seatId because the hot-path query is "is this seat currently held?".
 * The reverse direction (reservation → seat) is already carried by the
 * Postgres {@code reservations.seat_id} column, so no reverse Redis index
 * is needed.
 */
@Component
@RequiredArgsConstructor
public class ReservationHoldStore {

    private final StringRedisTemplate redis;

    /**
     * Attempts to place a TTL hold on the seat. Returns {@code true} if this
     * caller acquired the hold, {@code false} if another caller already holds
     * it (or held it within the TTL window).
     */
    public boolean tryHold(UUID seatId, UUID reservationId, Duration ttl) {
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(key(seatId), reservationId.toString(), ttl);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * Releases the hold for a seat. Idempotent — DEL on a missing key is a
     * no-op, so this is safe to call whether the key is still live, already
     * TTL-expired, or never existed.
     */
    public void release(UUID seatId) {
        redis.delete(key(seatId));
    }

    private String key(UUID seatId) {
        return "hold:seat:" + seatId;
    }
}
