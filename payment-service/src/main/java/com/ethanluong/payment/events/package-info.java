/**
 * This service's own copies of the saga wire contracts, duplicated from
 * reservation-service's {@code saga.events} DELIBERATELY per D1: no shared
 * contracts jar, so the two services' release cycles stay uncoupled.
 *
 * <p>Wire compatibility is governed by the additive-only schema rules in
 * REFOCUS-DESIGN §2 — any change to these classes must land in BOTH services'
 * copies in the same change set. The extraction itself changed nothing on the
 * wire: envelope shape, event types, and topic names are byte-identical to
 * what the in-process component produced.
 */
package com.ethanluong.payment.events;
