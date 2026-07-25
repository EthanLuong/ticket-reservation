import { Link } from 'react-router-dom';
import { useCountdown } from '../lib/useCountdown';

interface HoldBannerProps {
  seatLabel: string;
  expiresAt: string;
  onExpire(): void;
}

export default function HoldBanner({ seatLabel, expiresAt, onExpire }: HoldBannerProps) {
  const remaining = useCountdown(expiresAt, onExpire);
  const expired = remaining === '0:00';

  return (
    <div className="mb-6 flex flex-wrap items-center justify-between gap-2 rounded border border-[var(--accent-border)] bg-[var(--accent-bg)] px-4 py-3">
      <p className="text-sm text-[var(--text-h)]">
        Seat <strong>{seatLabel}</strong> held —{' '}
        {expired ? 'expiring…' : <>expires in <span className="font-mono">{remaining}</span></>}
      </p>
      <Link to="/account" className="text-sm font-medium text-[var(--accent)] underline">
        View in account
      </Link>
    </div>
  );
}
