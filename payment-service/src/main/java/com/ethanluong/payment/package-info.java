/**
 * payment-service root — the R2 extraction of the Phase 2a in-process payment
 * component (REFOCUS-DESIGN §3). What Phase 2a called "split-ready" is now split.
 *
 * <p><b>Import discipline (now enforced by the artifact boundary, not review):</b>
 * this service owns everything it touches:
 * <ul>
 *   <li>{@code events.*} — this service's OWN copies of the wire contracts,
 *       duplicated deliberately per D1. See that package's package-info for the
 *       governance rule.</li>
 *   <li>{@code entity.OutboxEntry} + {@code repository.OutboxEntryRepository} +
 *       {@code OutboxPublisher} — this service's OWN outbox machinery, copied
 *       (not shared) from reservation-service: own database, own relay. The
 *       Phase 2a "sanctioned shared outbox" arrangement ended with the split.</li>
 * </ul>
 * Nothing here can reference reservation-service code — there is no shared
 * artifact to reach it through, which is the point: the only contact surface
 * is {@code payment.cmd}/{@code payment.evt} on the wire.
 */
package com.ethanluong.payment;
