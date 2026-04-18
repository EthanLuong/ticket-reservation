package com.ethanluong.ticketreservation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled sweeper that releases reservations whose TTL hold has lapsed.
 *
 * <p>Uses {@code fixedDelay} (not {@code fixedRate}) so sweeps never overlap:
 * the next run starts N ms <em>after</em> the previous one completes, which
 * guarantees serial execution on a single JVM.
 *
 * <p>In a multi-instance deploy, two JVMs could still sweep simultaneously —
 * safe today because {@code sweepExpired()} is idempotent and the seat
 * {@code @Version} catches any concurrent state change. At higher scale,
 * introduce ShedLock or SELECT FOR UPDATE SKIP LOCKED to partition work.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReservationExpirySweeper {

    private final ReservationService reservationService;

    @Scheduled(fixedDelayString = "${app.reservation.sweep-interval-ms}")
    public void sweep() {
        int count = reservationService.sweepExpired();
        if (count > 0) {
            log.info("Expired {} reservation(s) past their TTL hold", count);
        }
    }
}
