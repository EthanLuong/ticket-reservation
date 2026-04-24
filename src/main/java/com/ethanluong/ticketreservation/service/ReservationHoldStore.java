package com.ethanluong.ticketreservation.service;

import com.ethanluong.ticketreservation.api.exception.SeatContentionException;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis-backed coordination for reservation holds. Two primitives, two purposes:
 *
 * <ul>
 *   <li><b>TTL hold</b> at {@code hold:seat:{seatId}} — the business lock.
 *       Lives for the configured hold duration (10 min). Atomic via SET NX EX.</li>
 *   <li><b>Critical-section lock</b> at {@code lock:seat:{seatId}} — the
 *       Redisson {@code RLock}. Held only for the duration of a reserve/cancel
 *       op (&lt;2 s). Serializes operations across JVMs so the reconciliation
 *       logic inside the critical section is safe from races.</li>
 * </ul>
 *
 * <p>Key schemas are indexed by seatId because the hot-path query is
 * "is this seat currently held / being modified?". The reverse direction
 * (reservation → seat) is already carried by the Postgres {@code seat_id}
 * column, so no reverse Redis index is needed.
 */
@Component
@RequiredArgsConstructor
public class ReservationHoldStore {

    private static final long LOCK_WAIT_MS = 100L;
    private static final long LOCK_LEASE_MS = 2_000L;

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;

    /**
     * Runs {@code critical} while holding the Redisson lock for this seat.
     * Throws {@link SeatContentionException} if the lock cannot be acquired
     * within {@value #LOCK_WAIT_MS} ms — callers should treat that as a
     * transient 409 and retry.
     *
     * <p>Uses {@link RLock#tryLock(long, long, TimeUnit)} with an explicit
     * 2 s lease (not the watchdog-renewed default) so a crashed holder
     * auto-releases in bounded time without relying on a background thread.
     */
    public <T> T withSeatLock(UUID seatId, Supplier<T> critical) {
        RLock lock = redisson.getLock(lockKey(seatId));
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_MS, LOCK_LEASE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SeatContentionException(seatId);
        }
        if (!acquired) {
            throw new SeatContentionException(seatId);
        }
        try {
            return critical.get();
        } finally {
            // Defensive — if the 2 s lease expired during a long GC pause,
            // the lock is no longer ours and unlock() would throw.
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Attempts to place a TTL hold on the seat. Atomic SET NX EX — returns
     * {@code true} if this caller acquired the hold, {@code false} if another
     * caller already holds it.
     */
    public boolean tryHold(UUID seatId, UUID reservationId, Duration ttl) {
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(holdKey(seatId), reservationId.toString(), ttl);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * Returns {@code true} if a TTL hold currently exists for the seat.
     * Used by lazy reconciliation — a HELD reservation row in Postgres with
     * no Redis key means the hold has expired and the row should be flipped
     * to {@code EXPIRED} on read.
     */
    public boolean hasHold(UUID seatId) {
        return Boolean.TRUE.equals(redis.hasKey(holdKey(seatId)));
    }

    /**
     * Releases the hold for a seat. Idempotent — DEL on a missing key is a
     * no-op, so this is safe whether the key is still live, already
     * TTL-expired, or never existed.
     */
    public void release(UUID seatId) {
        redis.delete(holdKey(seatId));
    }

    private String holdKey(UUID seatId) {
        return "hold:seat:" + seatId;
    }

    private String lockKey(UUID seatId) {
        return "lock:seat:" + seatId;
    }
}
