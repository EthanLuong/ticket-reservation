import type { ReservationStatus } from '../lib/types';

/**
 * Reservation lifecycle chip. HELD pulses because it is genuinely in motion —
 * the payment saga settles it to CONFIRMED or CANCELLED server-side, and the
 * pages that render this chip poll while anything is unsettled.
 */
const META: Record<ReservationStatus, { label: string; chip: string; dot: string; pulse: boolean }> = {
  HELD: {
    label: 'Held · confirming',
    chip: 'border-[var(--warn-border)] bg-[var(--warn-bg)] text-[var(--text-h)]',
    dot: 'bg-[var(--warn)]',
    pulse: true,
  },
  CONFIRMED: {
    label: 'Confirmed',
    chip: 'border-[var(--ok-border)] bg-[var(--ok-bg)] text-[var(--text-h)]',
    dot: 'bg-[var(--ok)]',
    pulse: false,
  },
  EXPIRED: {
    label: 'Expired',
    chip: 'border-[var(--border)] bg-transparent text-[var(--text)] opacity-70',
    dot: 'bg-[var(--border)]',
    pulse: false,
  },
  CANCELLED: {
    label: 'Cancelled',
    chip: 'border-[var(--border)] bg-transparent text-[var(--text)] opacity-70',
    dot: 'bg-[var(--border)]',
    pulse: false,
  },
};

export default function StatusChip({ status }: { status: ReservationStatus }) {
  const meta = META[status];
  return (
    <span
      className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded-full border px-2.5 py-0.5 text-xs font-medium ${meta.chip}`}
    >
      <span
        className={`inline-block h-2 w-2 rounded-full ${meta.dot} ${meta.pulse ? 'status-dot-pulse' : ''}`}
      />
      {meta.label}
    </span>
  );
}
