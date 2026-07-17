import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, toastFor } from '../lib/api';
import { useAuth } from '../auth/AuthContext';
import { useToast } from '../components/Toast';
import type { ReservationResponse, ReservationStatus } from '../lib/types';

const dateFormatter = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

const STATUS_STYLES: Record<ReservationStatus, string> = {
  HELD: 'border-[var(--accent-border)] bg-[var(--accent-bg)] text-[var(--text-h)]',
  CONFIRMED: 'border-[var(--border)] bg-[var(--code-bg)] text-[var(--text-h)]',
  EXPIRED: 'border-[var(--border)] bg-transparent text-[var(--text)] opacity-60',
  CANCELLED: 'border-[var(--border)] bg-transparent text-[var(--text)] opacity-60 line-through',
};

function remainingMs(expiresAt: string): number {
  return new Date(expiresAt).getTime() - Date.now();
}

function formatRemaining(ms: number): string {
  const totalSeconds = Math.max(0, Math.ceil(ms / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

/**
 * Same recompute-from-`expiresAt` approach as HoldBanner — never a locally
 * decremented counter — so the countdown stays honest if the tab is
 * backgrounded/throttled. Calls onExpire once when it crosses zero.
 */
function Countdown({ expiresAt, onExpire }: { expiresAt: string; onExpire(): void }) {
  const [remaining, setRemaining] = useState(() => remainingMs(expiresAt));
  const onExpireRef = useRef(onExpire);

  useEffect(() => {
    onExpireRef.current = onExpire;
  }, [onExpire]);

  useEffect(() => {
    setRemaining(remainingMs(expiresAt));
    const interval = setInterval(() => {
      const next = remainingMs(expiresAt);
      setRemaining(next);
      if (next <= 0) {
        clearInterval(interval);
        onExpireRef.current();
      }
    }, 500);
    return () => clearInterval(interval);
  }, [expiresAt]);

  return <span className="font-mono">{formatRemaining(remaining)}</span>;
}

export default function AccountPage() {
  const { email, logout } = useAuth();
  const { showToast } = useToast();

  const [reservations, setReservations] = useState<ReservationResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [cancellingId, setCancellingId] = useState<string | null>(null);

  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const load = useCallback((opts?: { silent?: boolean }) => {
    if (!opts?.silent) {
      setLoading(true);
      setError(null);
    }
    return api
      .myReservations()
      .then((res) => {
        if (!mountedRef.current) return;
        setReservations(res);
      })
      .catch((err) => {
        if (!mountedRef.current) return;
        // Background (silent) refetches — e.g. a countdown hitting zero —
        // fail quietly rather than clobbering a working table with an error.
        if (!opts?.silent) setError(toastFor(err));
      })
      .finally(() => {
        if (!mountedRef.current) return;
        if (!opts?.silent) setLoading(false);
      });
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const handleExpire = useCallback(() => {
    load({ silent: true });
  }, [load]);

  const handleCancel = useCallback(
    async (id: string) => {
      if (!window.confirm('Cancel this reservation?')) return;
      setCancellingId(id);
      try {
        await api.cancel(id);
        if (!mountedRef.current) return;
        await load({ silent: true });
      } catch (err) {
        if (!mountedRef.current) return;
        showToast(toastFor(err));
      } finally {
        if (mountedRef.current) setCancellingId(null);
      }
    },
    [load, showToast],
  );

  return (
    <div className="mx-auto mt-16 max-w-3xl px-4 pb-16">
      <div className="mb-8 flex items-center justify-between">
        <div>
          <h1 className="!mb-1">Account</h1>
          {email && <p className="text-sm text-[var(--text)]">{email}</p>}
        </div>
        <button
          type="button"
          onClick={logout}
          className="rounded border border-[var(--border)] px-3 py-1.5 text-sm hover:border-[var(--accent)]"
        >
          Log out
        </button>
      </div>

      <h2 className="!text-base">My Reservations</h2>

      {loading && (
        <div className="flex flex-col gap-3">
          {[0, 1, 2].map((i) => (
            <div
              key={i}
              className="h-12 animate-pulse rounded border border-[var(--border)] bg-[var(--code-bg)]"
            />
          ))}
        </div>
      )}

      {!loading && error && (
        <div className="flex flex-col items-center gap-3 py-12">
          <p className="text-sm text-red-600">{error}</p>
          <button
            type="button"
            onClick={() => load()}
            className="rounded bg-[var(--accent)] px-4 py-2 text-white"
          >
            Retry
          </button>
        </div>
      )}

      {!loading && !error && reservations.length === 0 && (
        <p className="py-12 text-center text-sm text-[var(--text)]">
          No reservations yet.{' '}
          <Link to="/events" className="text-[var(--accent)] underline">
            Browse events
          </Link>
        </p>
      )}

      {!loading && !error && reservations.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-sm">
            <thead>
              <tr className="border-b border-[var(--border)] text-left text-[var(--text)]">
                <th className="py-2 pr-4 font-medium">Seat</th>
                <th className="py-2 pr-4 font-medium">Status</th>
                <th className="py-2 pr-4 font-medium">Created</th>
                <th className="py-2 pr-4 font-medium">Expires</th>
                <th className="py-2 pr-4 font-medium"></th>
              </tr>
            </thead>
            <tbody>
              {reservations.map((r) => (
                <tr key={r.id} className="border-b border-[var(--border)]">
                  <td className="py-2 pr-4 font-mono text-xs text-[var(--text-h)]">
                    {r.seatId.slice(0, 8)}
                  </td>
                  <td className="py-2 pr-4">
                    <span
                      className={`rounded border px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[r.status]}`}
                    >
                      {r.status}
                    </span>
                  </td>
                  <td className="py-2 pr-4 text-[var(--text)]">
                    {dateFormatter.format(new Date(r.createdAt))}
                  </td>
                  <td className="py-2 pr-4 text-[var(--text)]">
                    {r.status === 'HELD' && r.expiresAt ? (
                      <Countdown expiresAt={r.expiresAt} onExpire={handleExpire} />
                    ) : (
                      '—'
                    )}
                  </td>
                  <td className="py-2 pr-4 text-right">
                    {(r.status === 'HELD' || r.status === 'CONFIRMED') && (
                      <button
                        type="button"
                        disabled={cancellingId === r.id}
                        onClick={() => handleCancel(r.id)}
                        className="rounded border border-[var(--border)] px-3 py-1 text-xs hover:border-[var(--accent)] disabled:opacity-50"
                      >
                        {cancellingId === r.id ? 'Cancelling…' : 'Cancel'}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
