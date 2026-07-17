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
