# Ticket Reservation — frontend

React + TypeScript + Vite + Tailwind client for the ticket-reservation backend.

## What it does

- **Browse events** and pick seats from a live grid (5s polling, paused when the tab is hidden).
- **Seat holds**: reserving a seat starts a time-limited hold (Redis TTL server-side) with a countdown.
- **Live saga status**: reservation state (`HELD → CONFIRMED / CANCELLED`) settles **asynchronously** via the backend's payment saga — the account page and the hold banner poll while anything is unsettled, so confirmations and compensations appear without a refresh. In-flight states pulse; settled states don't.
- JWT auth (register / sign in), my-reservations view with cancel (3-day cutoff before the event), 404 page, light/dark theme.

## Structure

```
src/
  auth/        AuthContext (JWT in localStorage), RequireAuth route guard
  components/  AppShell (header/nav), SeatGrid, HoldBanner, StatusChip, Toast
  lib/         api.ts (fetch client + ProblemDetail → toast copy), types, useCountdown
  pages/       Login, Events, EventDetail, Account, NotFound
```

Conventions worth keeping:

- **Error copy lives in `toastFor()`** (`lib/api.ts`), keyed off backend ProblemDetail type URIs — components never invent copy.
- **Countdowns recompute from `expiresAt`** (server clock), never a local decrement — honest under tab throttling.
- **Background polls fail silently**; only initial loads surface blocking errors.
- Status colors are semantic CSS vars (`--ok`, `--warn`, `--danger`) defined for light and dark in `index.css`.

## Run

```bash
npm install
npm run dev        # expects the backend on the same origin, or set VITE_API_BASE
npm run build      # tsc + vite build → dist/
npm run lint       # oxlint
```

`VITE_API_BASE` (optional) points API calls at a different origin, e.g. `VITE_API_BASE=http://localhost:8080`.
