# ADR 0004 — Real-time seat status: visibility-aware polling, not SSE

**Status:** Proposed (drafted by Claude 2026-07-27 for ratification — the 🤝 decision in roadmap M2; overturn freely)
**Date:** 2026-07-27
**Phase:** 3 (frontend), revisit trigger in Phase 4/5

## Context

The seat grid and the account page must reflect state that changes without user action: other buyers taking seats, and — since Phase 2a — the payment saga settling reservations (`HELD → CONFIRMED / CANCELLED`) server-side. Design §4.5 left the mechanism open: keep the 5-second polling shipped in frontend v1, or upgrade to Server-Sent Events.

What the frontend already does (v1 + the 2026-07-27 saga-awareness pass):

- Polls seats and unsettled reservations every 5s, **only while the tab is visible** (`visibilitychange` pause), and **only while something is actually unsettled** on the account page.
- Fails background polls silently; refetches immediately on user-action errors (contention 409 → instant grid refresh).

## Decision

**Keep polling.** 5s interval, visibility-aware, unsettled-gated — as implemented.

Reasoning, in the order that actually decides it:

1. **The data's tempo doesn't justify push.** Holds live 10 minutes; the payment saga settles in ~seconds-to-30s. A 5s poll bounds staleness at 5s, and the only staleness that *matters* (clicking an already-taken seat) is already handled better by the 409 + toast + instant refetch path than any push channel would — contention is discovered at write time regardless of transport.
2. **Polling is the cheap side of every axis this project cares about.** No connection lifecycle, no `Last-Event-ID` resume logic, no proxy/timeout configuration, no per-tab connection budget (browsers cap ~6 SSE streams per origin over HTTP/1.1). GETs are stateless, cacheable, and multi-instance-safe on day one — SSE would need sticky sessions or a Redis pub/sub fan-out the moment M3's second instance appears, turning a UI nicety into distributed infrastructure.
3. **The interview story is better as a decision than a feature.** "I measured the requirement — 5s staleness bound on 10-minute holds — and chose the boring option, documented the revisit triggers" demonstrates judgment; a hand-rolled SSE channel demonstrates enthusiasm.

## Revisit triggers

Re-open this ADR if any of these become true:

- A feature needs sub-second push semantics (live seat-map for a flash-sale event, collaborative viewing).
- Polling cost becomes measurable: N tabs × 12 req/min against `/api/seats` shows up in M3 load tests (mitigation short of SSE: ETag/304 on the seats endpoint).
- M3's multi-instance work builds a Redis pub/sub layer *anyway* — at that point SSE's marginal cost drops to the endpoint itself.

## Consequences

- Frontend stays as shipped; no new endpoints.
- Seat-taken staleness is bounded at 5s and self-corrects on interaction; saga transitions appear within one poll tick.
- If ratified, check off roadmap M2's "real-time seat status decision" and note the ADR number; if overturned, the SSE work should be scheduled *after* M3's multi-instance infrastructure, not before, per trigger #3.
