# Frontend v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** React SPA portfolio demo layer over the existing Spring Boot API — login, events, seat selection with live holds, account/reservations.

**Architecture:** Vite + React + TS in `frontend/`. One fetch wrapper owns auth headers and RFC 7807 error mapping; pages consume typed API functions. No state library — auth in React context, everything else local state + refetch. Design doc: `docs/FRONTEND-V1-DESIGN.md`.

**Tech Stack:** React 19, Vite, TypeScript (strict), react-router-dom, Tailwind. No axios, no Redux/Zustand, no component libraries. *(Amended 2026-07-16 during execution: plan originally said React 18; `npm create vite` scaffolds 19, which is current stable — accepted rather than downgraded.)*

## Global Constraints

- Backend base URL: `http://localhost:8080` (dev), configurable via `VITE_API_BASE`.
- JWT stored in `localStorage` under key `tr.jwt`.
- All server timestamps are ISO-8601 with offset (`OffsetDateTime`); parse with `new Date(...)`, never string-slice.
- Every page must render sensibly in loading / error / empty states — no blank screens.
- Toast messages come from ProblemDetail mapping in `api.ts` only — components never invent error copy.
- Task 1 is **hand-written by Ethan (teach-mode)**; Tasks 2–7 are LLM-generated and reviewed by behavior.
- Verification baseline for every task: `npm run build` passes (tsc strict), then the listed manual checks against `docker compose up` + running backend.

---

### Task 1: Backend CORS (Ethan, teach-mode — not LLM scope)

**Files:**
- Modify: `src/main/java/com/ethanluong/ticketreservation/config/SecurityConfig.java`

**Interfaces:**
- Produces: browser fetches from `http://localhost:5173` succeed (preflight `OPTIONS` returns `Access-Control-Allow-Origin`).

- [ ] **Step 1: Add a CorsConfigurationSource bean and enable it in the filter chain**

```java
@Bean
CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("http://localhost:5173"));
    config.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
}
```
and in the `SecurityFilterChain`: `.cors(Customizer.withDefaults())`.

- [ ] **Step 2: Verify preflight**

Run: `curl -i -X OPTIONS http://localhost:8080/api/events -H "Origin: http://localhost:5173" -H "Access-Control-Request-Method: GET"`
Expected: `200` with `Access-Control-Allow-Origin: http://localhost:5173` header.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/ethanluong/ticketreservation/config/SecurityConfig.java
git commit -m "feat: CORS for frontend dev origin"
```

---

### Task 2: Scaffold + types + API wrapper

**Files:**
- Create: `frontend/` (Vite scaffold), `frontend/src/lib/types.ts`, `frontend/src/lib/api.ts`, `frontend/.env.development` (`VITE_API_BASE=http://localhost:8080`)

**Interfaces:**
- Produces (all later tasks consume): the types and functions below, exactly as named.

- [ ] **Step 1: Scaffold**

Run: `npm create vite@latest frontend -- --template react-ts` then `cd frontend && npm i react-router-dom && npm i -D tailwindcss @tailwindcss/vite` (wire Tailwind per its Vite guide).

- [ ] **Step 2: Write `types.ts` — mirrors backend DTO records exactly**

```ts
export type SeatStatus = 'AVAILABLE' | 'HELD' | 'SOLD';
export type ReservationStatus = 'HELD' | 'CONFIRMED' | 'EXPIRED' | 'CANCELLED';

export interface AuthResponse { accessToken: string; tokenType: string; expiresInSeconds: number; }
export interface EventResponse { id: string; name: string; description: string; venue: string; startsAt: string; endsAt: string; }
export interface SeatResponse { id: string; eventId: string; seatLabel: string; priceCents: number; status: SeatStatus; }
export interface ReservationResponse { id: string; seatId: string; userId: string; status: ReservationStatus; expiresAt: string | null; createdAt: string; }
/** Spring Page<EventResponse> — only the fields we consume (I11: shape is brittle, keep minimal). */
export interface PageResponse<T> { content: T[]; totalPages: number; number: number; totalElements: number; }
export interface ProblemDetail { type?: string; title?: string; status?: number; detail?: string; }
export class ApiError extends Error { constructor(public problem: ProblemDetail, public httpStatus: number) { super(problem.detail ?? problem.title ?? `HTTP ${httpStatus}`); } }
```

- [ ] **Step 3: Write `api.ts` — the single wrapper + typed endpoint functions**

```ts
import { ApiError, type AuthResponse, type EventResponse, type PageResponse, type ProblemDetail, type ReservationResponse, type SeatResponse, type SeatStatus } from './types';

const BASE = import.meta.env.VITE_API_BASE ?? '';
const JWT_KEY = 'tr.jwt';

export const getToken = () => localStorage.getItem(JWT_KEY);
export const setToken = (t: string) => localStorage.setItem(JWT_KEY, t);
export const clearToken = () => localStorage.removeItem(JWT_KEY);

/** sub = userId, email = custom claim (see JwtService). No display name in v1. */
export function claims(): { userId: string; email: string } | null {
  const t = getToken();
  if (!t) return null;
  try { const p = JSON.parse(atob(t.split('.')[1])); return { userId: p.sub, email: p.email }; }
  catch { return null; }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json', ...(init.headers as Record<string, string>) };
  const token = getToken();
  if (token) headers['Authorization'] = `Bearer ${token}`;
  const res = await fetch(`${BASE}${path}`, { ...init, headers });
  if (res.status === 401 || res.status === 403) { clearToken(); window.location.assign('/login'); throw new ApiError({ title: 'Session expired' }, res.status); }
  if (!res.ok) {
    let problem: ProblemDetail = {};
    try { problem = await res.json(); } catch { /* empty body (I9) — fall through */ }
    throw new ApiError(problem, res.status);
  }
  return res.status === 204 ? (undefined as T) : res.json();
}

/** ProblemDetail type URI suffix → user-facing toast copy. Components use this, never invent copy. */
export function toastFor(e: unknown): string {
  if (!(e instanceof ApiError)) return 'Something went wrong.';
  const t = e.problem.type ?? '';
  if (t.endsWith('seat-contention')) return 'Someone beat you to that seat — pick another.';
  if (t.endsWith('seat-not-available')) return 'That seat was just taken.';
  if (t.endsWith('seat-operation-failed')) return e.problem.detail ?? 'That reservation can no longer be changed.';
  if (t.endsWith('cancellation-window-closed')) return 'Too close to the event to cancel (3-day cutoff).';
  if (t.endsWith('not-found')) return 'Not found.';
  return e.problem.detail ?? 'Request failed.';
}

export const api = {
  register: (email: string, password: string, displayName: string) =>
    request<AuthResponse>('/api/auth/register', { method: 'POST', body: JSON.stringify({ email, password, displayName }) }),
  login: (email: string, password: string) =>
    request<AuthResponse>('/api/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }),
  events: (page = 0) => request<PageResponse<EventResponse>>(`/api/events?page=${page}&size=12`),
  event: (id: string) => request<EventResponse>(`/api/events/${id}`),
  seats: (eventId: string, status?: SeatStatus) =>
    request<SeatResponse[]>(`/api/seats?eventId=${eventId}${status ? `&status=${status}` : ''}`),
  reserve: (seatId: string) => request<ReservationResponse>('/api/reservations', { method: 'POST', body: JSON.stringify({ seatId }) }),
  cancel: (reservationId: string) => request<ReservationResponse>(`/api/reservations/${reservationId}`, { method: 'DELETE' }),
  myReservations: () => request<ReservationResponse[]>('/api/reservations/me'),
};
```

> ⚠️ Before finalizing: verify the reserve request body field name and auth request DTOs against `ReservationController`/`AuthController` request records — adjust `api.ts` if the backend names differ. Also confirm exact ProblemDetail type suffixes in `GlobalExceptionHandler` and align `toastFor`.

- [ ] **Step 4: Verify** — `npm run build` passes.

- [ ] **Step 5: Commit** — `git add frontend && git commit -m "feat(frontend): scaffold, types, api wrapper"`

---

### Task 3: Auth — context, login/register page, route guard

**Files:**
- Create: `frontend/src/auth/AuthContext.tsx`, `frontend/src/pages/LoginPage.tsx`, `frontend/src/auth/RequireAuth.tsx`, `frontend/src/App.tsx` (router)

**Interfaces:**
- Consumes: `api.login`, `api.register`, `setToken`, `clearToken`, `claims()` from Task 2.
- Produces: `useAuth(): { email: string | null; login(e,p): Promise<void>; register(e,p,name): Promise<void>; logout(): void }`; `<RequireAuth>` route wrapper; routes `/login`, `/events`, `/events/:id`, `/account`.

- [ ] **Step 1: Implement** — AuthContext wraps token state; `login`/`register` call the API, `setToken`, populate `email` from `claims()`. `logout` clears token, navigates `/login`. `RequireAuth` redirects to `/login` when `claims()` is null. LoginPage: two tabs (Sign in / Create account); disabled submit while pending; on `ApiError` show `toastFor(e)` inline under the form; on success navigate `/events`. Register tab fields: email, password, display name.
- [ ] **Step 2: Verify** — `npm run build`; manual: register a new user (lands on /events), logout, login again, hit `/account` logged-out (redirects to `/login`), wrong password shows toast copy not a blank screen.
- [ ] **Step 3: Commit** — `git commit -m "feat(frontend): auth context, login/register, route guard"`

---

### Task 4: Event listing

**Files:**
- Create: `frontend/src/pages/EventsPage.tsx`

**Interfaces:**
- Consumes: `api.events(page)`, `PageResponse<EventResponse>`.
- Produces: event card grid; clicking a card navigates to `/events/:id`.

- [ ] **Step 1: Implement** — fetch page 0 on mount; card shows name, venue, `startsAt` formatted via `Intl.DateTimeFormat`; filter input does client-side case-insensitive match on name+venue over the loaded page; Prev/Next buttons drive `page` state off `totalPages`/`number`; states: skeleton while loading, retry button on error, "No events yet" when `content` is empty, "No matches" when filter excludes all.
- [ ] **Step 2: Verify** — `npm run build`; manual: list renders against seeded DB, filter narrows live, pagination works or hides itself at 1 page.
- [ ] **Step 3: Commit** — `git commit -m "feat(frontend): event listing with search + paging"`

---

### Task 5: Seat selection + hold banner (demo centerpiece)

**Files:**
- Create: `frontend/src/pages/EventDetailPage.tsx`, `frontend/src/components/SeatGrid.tsx`, `frontend/src/components/HoldBanner.tsx`, `frontend/src/components/Toast.tsx`

**Interfaces:**
- Consumes: `api.event`, `api.seats`, `api.reserve`, `toastFor`.
- Produces: `<Toast>` (also used by Task 6): `showToast(msg: string)` via simple context or callback prop.

- [ ] **Step 1: Implement** —
  - SeatGrid: buttons per seat, sorted by `seatLabel` (backend pre-sorts); color by status — AVAILABLE clickable, HELD/SOLD disabled with distinct styles; legend under grid.
  - Click AVAILABLE seat → `api.reserve(seat.id)`; success → HoldBanner appears with seat label and **countdown to `expiresAt`** (interval recompute from `Date`, never a decrementing counter); banner links to `/account`; countdown hitting 0 → banner clears + seat refetch.
  - `ApiError` on reserve → `showToast(toastFor(e))` + immediate seat refetch (the grid was stale — that's the contention demo).
  - Poll `api.seats(eventId)` every 5s while page visible (`document.visibilityState` check); no polling when tab hidden.
- [ ] **Step 2: Verify** — `npm run build`; manual **two-browser contention script**: window A and B on same event, both click seat X → A gets banner, B gets toast + X flips to HELD within one poll; wait 10 min (or lower TTL locally) → hold expires, seat returns to AVAILABLE.
- [ ] **Step 3: Commit** — `git commit -m "feat(frontend): seat selection, hold countdown, contention toast"`

---

### Task 6: Account page — user info, reservations, cancel

**Files:**
- Create: `frontend/src/pages/AccountPage.tsx`

**Interfaces:**
- Consumes: `claims()`, `api.myReservations`, `api.cancel`, `toastFor`, `<Toast>`.

- [ ] **Step 1: Implement** — header shows email (JWT claim; no display name in v1 — the API doesn't return one); table of reservations: seat id (short form), status badge, `createdAt`, countdown on HELD rows (same recompute-from-`expiresAt` approach as HoldBanner), Cancel button on HELD/CONFIRMED rows; Cancel → confirm dialog → `api.cancel` → refetch list; `ApiError` → toast via `toastFor` (this surfaces the `cancellation-window-closed` copy); empty state: "No reservations yet" linking to `/events`; logout button.
- [ ] **Step 2: Verify** — `npm run build`; manual: hold a seat then see it listed with ticking countdown; cancel it (row flips CANCELLED, seat grid frees after poll); attempt double-cancel via stale second tab → 409 toast, not a crash.
- [ ] **Step 3: Commit** — `git commit -m "feat(frontend): account page with reservations + cancel"`

---

### Task 7: Polish + README + demo script

**Files:**
- Modify: `frontend/src/*` (empty/error state sweep), `README.md` (root — add Frontend section)

- [ ] **Step 1: Sweep** — verify every page's loading/error/empty states exist (Global Constraints); mobile-width sanity pass at 390px (grid wraps, no horizontal scroll); page `<title>`s.
- [ ] **Step 2: README** — add: how to run (`npm run dev` + backend prereqs incl. CORS), the two-browser demo script from Task 5, screenshot placeholders for Ethan to fill after first run.
- [ ] **Step 3: Full acceptance** — run the design doc's manual demo script end-to-end; `npm run build` clean.
- [ ] **Step 4: Commit** — `git commit -m "docs(frontend): README + polish pass"`

---

## Self-review (done at write time)

- **Spec coverage:** all 4 v1 pages + seat selection ✓ · error-contract mapping ✓ · hold countdown from server time ✓ · polling ✓ · CORS prerequisite ✓ · I9/I11 fast-follows intentionally *not* planned (spec lists them as non-blockers).
- **Placeholders:** none — the one deliberate verification note (⚠️ Task 2, request-body field names) is a check step, not deferred work.
- **Type consistency:** `toastFor`/`showToast`/`claims()`/api function names identical across Tasks 2–6 ✓.
