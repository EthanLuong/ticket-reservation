import { useEffect, useRef, useState } from 'react';

function remainingMs(expiresAt: string): number {
  return new Date(expiresAt).getTime() - Date.now();
}

export function formatRemaining(ms: number): string {
  const totalSeconds = Math.max(0, Math.ceil(ms / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

/**
 * Countdown is always recomputed from `expiresAt` (server clock), never a
 * locally-decremented counter — keeps it honest if the tab is
 * backgrounded/throttled or the interval drifts. Calls `onExpire` once when
 * it crosses zero.
 */
export function useCountdown(expiresAt: string, onExpire: () => void): string {
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

  return formatRemaining(remaining);
}
