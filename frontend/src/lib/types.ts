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
