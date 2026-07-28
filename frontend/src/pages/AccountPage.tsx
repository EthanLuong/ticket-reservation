import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, toastFor } from '../lib/api';
import { useToast } from '../components/Toast';
import StatusChip from '../components/StatusChip';
import { useCountdown } from '../lib/useCountdown';
import type { ReservationResponse } from '../lib/types';

const dateFormatter = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

/** Poll cadence while any reservation is unsettled — the saga confirms or cancels server-side. */
const POLL_MS = 5000;

const isSettled = (r: ReservationResponse) => r.status !== 'HELD';

function Countdown({ expiresAt, onExpire }: { expiresAt: string; onExpire(): void }) {
  const remaining = useCountdown(expiresAt, onExpire);
  return <span className="font-mono">{remaining}</span>;
}

function ReservationRow({
  reservation,
  cancelling,
  onCancel,
  onExpire,
}: {
  reservation: ReservationResponse;
  cancelling: boolean;
  onCancel(id: string): void;
  onExpire(): void;
}) {
  const r = reservation;
  return (
    <tr className="border-b border-[var(--border)]">
      <td className="py-2.5 pr-4 font-mono text-xs text-[var(--text-h)]">#{r.seatId.slice(0, 8)}</td>
      <td className="py-2.5 pr-4">
        <StatusChip status={r.status} />
      </td>
      <td className="py-2.5 pr-4 text-[var(--text)]">{dateFormatter.format(new Date(r.createdAt))}</td>
      <td className="py-2.5 pr-4 text-[var(--text)]">
        {r.status === 'HELD' && r.expiresAt ? (
          <Countdown expiresAt={r.expiresAt} onExpire={onExpire} />
        ) : (
          '—'
        )}
      </td>
      <td className="py-2.5 text-right">
        {(r.status === 'HELD' || r.status === 'CONFIRMED') && (
          <button
            type="button"
            disabled={cancelling}
            onClick={() => onCancel(r.id)}
            className="rounded border border-[var(--border)] px-3 py-1 text-xs hover:border-[var(--accent)] disabled:opacity-50"
          >
            {cancelling ? 'Cancelling…' : 'Cancel'}
          </button>
        )}
      </td>
    </tr>
  );
}

function ReservationTable({
  rows,
  cancellingId,
  onCancel,
  onExpire,
}: {
  rows: ReservationResponse[];
  cancellingId: string | null;
  onCancel(id: string): void;
  onExpire(): void;
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="border-b border-[var(--border)] text-left text-[var(--text)]">
            <th className="py-2 pr-4 font-medium">Seat</th>
            <th className="py-2 pr-4 font-medium">Status</th>
            <th className="py-2 pr-4 font-medium">Created</th>
            <th className="py-2 pr-4 font-medium">Hold expires</th>
            <th className="py-2"></th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <ReservationRow
              key={r.id}
              reservation={r}
              cancelling={cancellingId === r.id}
              onCancel={onCancel}
              onExpire={onExpire}
            />
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function AccountPage() {
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

  useEffect(() => {
    document.title = 'My reservations · Ticket Reservation';
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
        // Background refetches fail quietly rather than clobbering a working table.
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

  // Poll while the tab is visible and any reservation is unsettled — CONFIRMED
  // and CANCELLED arrive asynchronously from the payment saga, not from user actions.
  const hasUnsettled = useMemo(() => reservations.some((r) => !isSettled(r)), [reservations]);
  const loadRef = useRef(load);
  useEffect(() => {
    loadRef.current = load;
  }, [load]);

  useEffect(() => {
    if (!hasUnsettled) return;

    let intervalId: ReturnType<typeof setInterval> | null = null;

    function start() {
      if (intervalId !== null) return;
      intervalId = setInterval(() => loadRef.current({ silent: true }), POLL_MS);
    }
    function stop() {
      if (intervalId !== null) {
        clearInterval(intervalId);
        intervalId = null;
      }
    }
    function handleVisibilityChange() {
      if (document.visibilityState === 'hidden') {
        stop();
      } else {
        start();
        loadRef.current({ silent: true });
      }
    }

    if (document.visibilityState === 'visible') start();
    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => {
      stop();
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [hasUnsettled]);

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

  const active = useMemo(
    () => reservations.filter((r) => r.status === 'HELD' || r.status === 'CONFIRMED'),
    [reservations],
  );
  const past = useMemo(
    () => reservations.filter((r) => r.status === 'EXPIRED' || r.status === 'CANCELLED'),
    [reservations],
  );

  return (
    <div className="mx-auto mt-8 max-w-3xl px-4 pb-16">
      <h1 className="!text-3xl">My reservations</h1>

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
          <p className="text-sm text-[var(--danger)]">{error}</p>
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

      {!loading && !error && active.length > 0 && (
        <ReservationTable
          rows={active}
          cancellingId={cancellingId}
          onCancel={handleCancel}
          onExpire={handleExpire}
        />
      )}

      {!loading && !error && reservations.length > 0 && active.length === 0 && (
        <p className="py-6 text-sm text-[var(--text)]">
          Nothing active.{' '}
          <Link to="/events" className="text-[var(--accent)] underline">
            Browse events
          </Link>
        </p>
      )}

      {!loading && !error && past.length > 0 && (
        <details className="mt-8">
          <summary className="cursor-pointer text-sm font-medium text-[var(--text)]">
            Past reservations ({past.length})
          </summary>
          <div className="mt-3">
            <ReservationTable
              rows={past}
              cancellingId={cancellingId}
              onCancel={handleCancel}
              onExpire={handleExpire}
            />
          </div>
        </details>
      )}
    </div>
  );
}
