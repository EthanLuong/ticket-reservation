import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';

interface HoldBannerProps {
  seatLabel: string;
  expiresAt: string;
  onExpire(): void;
}

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
 * Countdown is always recomputed from `expiresAt` (server clock), never a
 * locally-decremented counter — keeps the banner honest if the tab is
 * backgrounded/throttled or the interval drifts.
 */
export default function HoldBanner({ seatLabel, expiresAt, onExpire }: HoldBannerProps) {
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

  const expired = remaining <= 0;

  return (
    <div className="mb-6 flex flex-wrap items-center justify-between gap-2 rounded border border-[var(--accent-border)] bg-[var(--accent-bg)] px-4 py-3">
      <p className="text-sm text-[var(--text-h)]">
        Seat <strong>{seatLabel}</strong> held —{' '}
        {expired ? 'expiring…' : <>expires in <span className="font-mono">{formatRemaining(remaining)}</span></>}
      </p>
      <Link to="/account" className="text-sm font-medium text-[var(--accent)] underline">
        View in account
      </Link>
    </div>
  );
}
