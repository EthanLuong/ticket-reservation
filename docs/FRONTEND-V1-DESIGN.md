# Frontend v1 — Design

**Date:** 2026-07-16 · **Status:** approved (design), not started (build)
**Purpose:** portfolio demo layer over the existing Spring Boot API. The backend stays the star;
the frontend makes its concurrency story (Redis holds, TTL, contention) visible and clickable.
LLM-generated; reviewed like a junior PR — run it, click it, read the API layer.

## Scope

**In (v1):** login/register, event listing with client-side search, seat selection with live
hold countdown, account page with reservations + cancel.
**Out (v1):** checkout/payment — blocked on `POST /reservations/{id}/confirm` (Phase 2);
server-side event search; admin/event-creation UI.

## Stack

React + Vite + TypeScript in `frontend/` inside this repo. Plain CSS or Tailwind only.
No state library — React context for auth, local state elsewhere. Deploys as static files.

## Pages

| Route | Page | Endpoints |
|---|---|---|
| `/login` | Login / Register (two tabs). Store JWT on success → redirect `/events` | `POST /api/auth/login`, `POST /api/auth/register` |
| `/events` | Paged event grid; client-side name/venue filter | `GET /api/events` |
| `/events/:id` | **Seat selection** — grid colored by `AVAILABLE / HELD / SOLD`; click → reserve → hold banner with countdown from `expiresAt`; link to `/account` | `GET /api/events/{id}`, `GET /api/seats?eventId=`, `POST /api/reservations` |
| `/account` | User info (JWT claims) + reservations table: status, countdown on HELD, Cancel button | `GET /api/reservations/me`, `DELETE /api/reservations/{id}` |

**Demo script this enables:** two browser windows on the same seat map; both click one seat;
one holds, the other gets a contention toast naming the RFC 7807 problem type. Cancel a
CONFIRMED row inside the 3-day window → distinct `cancellation-window-closed` toast.

## Cross-cutting

- **API client:** single `fetch` wrapper. Adds `Authorization: Bearer <jwt>`; parses RFC 7807
  bodies and maps `type` → user-facing toast (`seat-operation-failed`,
  `cancellation-window-closed`, seat-contention, not-found). Error contract is a feature.
- **Auth:** JWT in `localStorage`, decoded for display name/email. 401/403 → redirect `/login`.
  Known tradeoff vs httpOnly cookies — acceptable for demo, be ready to defend.
- **Hold countdown:** derived from server `expiresAt` only; on reaching zero, re-fetch. The
  client never owns time.
- **Polling:** seat map refetches on an interval (~5s) while open; no WebSockets in v1.

## Backend prerequisites (small, hand-written — not LLM scope)

1. CORS for the Vite dev origin (`http://localhost:5173`) in SecurityConfig.
2. Fast-follows surfaced by the UI (existing audit items, not blockers):
   - **I11** — stop serializing raw `Page<Event>`; wrap in a stable DTO.
   - **I9** — expired/missing JWT should return 401 + `WWW-Authenticate`, not empty 403.

## Testing / acceptance

- Manual demo script above runs clean against `docker compose up` + local backend.
- The LLM-built code must pass: `npm run build` (type-checks), and every page handles the
  loading / error / empty states (events empty, no reservations, expired hold).
- Review bar: Ethan reads and can explain `api.ts` (the wrapper) and the auth flow; component
  internals are reviewed by behavior, not line-by-line.

## Build order

1. Scaffold + API wrapper + auth (login/register/logout, route guard)
2. Events list
3. Seat selection + hold banner (the centerpiece — includes contention toast)
4. Account/reservations + cancel toasts
5. CORS + polish pass (empty states, mobile-width sanity)
