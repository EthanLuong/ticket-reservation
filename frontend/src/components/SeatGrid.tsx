import type { SeatResponse, SeatStatus } from '../lib/types';

interface SeatGridProps {
  seats: SeatResponse[];
  disabled?: boolean;
  onSelect(seat: SeatResponse): void;
}

const STATUS_STYLES: Record<SeatStatus, string> = {
  AVAILABLE:
    'border-[var(--border)] bg-transparent text-[var(--text-h)] hover:border-[var(--accent)] cursor-pointer',
  HELD: 'border-[var(--accent-border)] bg-[var(--accent-bg)] text-[var(--text)] opacity-70 cursor-not-allowed',
  SOLD: 'border-[var(--border)] bg-[var(--code-bg)] text-[var(--text)] opacity-50 line-through cursor-not-allowed',
};

const LEGEND: { status: SeatStatus; label: string }[] = [
  { status: 'AVAILABLE', label: 'Available' },
  { status: 'HELD', label: 'Held' },
  { status: 'SOLD', label: 'Sold' },
];

export default function SeatGrid({ seats, disabled, onSelect }: SeatGridProps) {
  return (
    <div>
      <div className="grid grid-cols-5 gap-2 sm:grid-cols-8 md:grid-cols-10">
        {seats.map((seat) => (
          <button
            key={seat.id}
            type="button"
            disabled={seat.status !== 'AVAILABLE' || disabled}
            onClick={() => onSelect(seat)}
            title={`${seat.seatLabel} — $${(seat.priceCents / 100).toFixed(2)} — ${seat.status}`}
            className={`rounded border px-2 py-2 text-xs font-medium transition-colors disabled:pointer-events-none ${STATUS_STYLES[seat.status]}`}
          >
            {seat.seatLabel}
          </button>
        ))}
      </div>

      <div className="mt-4 flex flex-wrap gap-4 text-xs text-[var(--text)]">
        {LEGEND.map(({ status, label }) => (
          <span key={status} className="flex items-center gap-1.5">
            <span className={`inline-block h-3 w-3 rounded border ${STATUS_STYLES[status]}`} />
            {label}
          </span>
        ))}
      </div>
    </div>
  );
}
