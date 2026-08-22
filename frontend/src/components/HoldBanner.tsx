import { Link } from 'react-router-dom';
import { useCountdown } from '../lib/useCountdown';
import type { ReservationStatus } from '../lib/types';

interface HoldBannerProps {
  seatLabel: string;
  expiresAt: string;
  /** Live saga state of the underlying reservation — the page polls it. */
  status: ReservationStatus;
  onExpire(): void;
  onDismiss(): void;
}

function HeldContent({ seatLabel, expiresAt, onExpire }: Pick<HoldBannerProps, 'seatLabel' | 'expiresAt' | 'onExpire'>) {
  const remaining = useCountdown(expiresAt, onExpire);
  const expired = remaining === '0:00';
  return (
    <p className="text-sm text-[var(--text-h)]">
      Seat <strong>{seatLabel}</strong> held —{' '}
      {expired ? 'expiring…' : <>confirming payment · hold expires in <span className="font-mono">{remaining}</span></>}
    </p>
  );
}

/**
 * Tracks the reservation through the payment saga, not just the hold TTL:
 * HELD counts down, CONFIRMED celebrates, CANCELLED/EXPIRED explains the release.
 */
export default function HoldBanner({ seatLabel, expiresAt, status, onExpire, onDismiss }: HoldBannerProps) {
  const frame =
    status === 'HELD'
      ? 'border-[var(--accent-border)] bg-[var(--accent-bg)]'
      : status === 'CONFIRMED'
        ? 'border-[var(--ok-border)] bg-[var(--ok-bg)]'
        : 'border-[var(--border)] bg-[var(--code-bg)]';

  return (
    <div className={`mb-6 flex flex-wrap items-center justify-between gap-2 rounded border px-4 py-3 ${frame}`}>
      {status === 'HELD' && <HeldContent seatLabel={seatLabel} expiresAt={expiresAt} onExpire={onExpire} />}

      {status === 'CONFIRMED' && (
        <p className="text-sm text-[var(--text-h)]">
          Seat <strong>{seatLabel}</strong> confirmed — payment went through.
        </p>
      )}

      {(status === 'CANCELLED' || status === 'EXPIRED') && (
        <p className="text-sm text-[var(--text-h)]">
          Seat <strong>{seatLabel}</strong> released — payment didn't complete. Pick another seat to try again.
        </p>
      )}

      <span className="flex items-center gap-3">
        <Link to="/account" className="text-sm font-medium text-[var(--accent)] underline">
          My reservations
        </Link>
        {status !== 'HELD' && (
          <button
            type="button"
            onClick={onDismiss}
            aria-label="Dismiss"
            className="rounded border border-[var(--border)] px-2 py-0.5 text-xs hover:border-[var(--accent)]"
          >
            Dismiss
          </button>
        )}
      </span>
    </div>
  );
}
