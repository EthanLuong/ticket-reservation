import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, toastFor } from '../lib/api';
import type { EventResponse } from '../lib/types';

const dateFormatter = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

export default function EventsPage() {
  const [page, setPage] = useState(0);
  const [events, setEvents] = useState<EventResponse[]>([]);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const navigate = useNavigate();

  useEffect(() => {
    document.title = 'Events · Ticket Reservation';
  }, []);

  const load = useCallback(
    (signal?: { cancelled: boolean }) => {
      setLoading(true);
      setError(null);
      api
        .events(page)
        .then((res) => {
          if (signal?.cancelled) return;
          setEvents(res.content);
          setTotalPages(res.totalPages);
        })
        .catch((err) => {
          if (signal?.cancelled) return;
          setError(toastFor(err));
        })
        .finally(() => {
          if (!signal?.cancelled) setLoading(false);
        });
    },
    [page],
  );

  useEffect(() => {
    const signal = { cancelled: false };
    load(signal);
    return () => {
      signal.cancelled = true;
    };
  }, [load]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return events;
    return events.filter(
      (e) => e.name.toLowerCase().includes(q) || e.venue.toLowerCase().includes(q),
    );
  }, [events, query]);

  return (
    <div className="mx-auto mt-8 max-w-3xl px-4 pb-16">
      <h1>Events</h1>

      <input
        type="text"
        placeholder="Filter by name or venue…"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        className="mb-6 w-full rounded border border-[var(--border)] bg-transparent px-3 py-2"
      />

      {loading && (
        <div className="flex flex-col gap-3">
          {[0, 1, 2].map((i) => (
            <div
              key={i}
              className="h-20 animate-pulse rounded border border-[var(--border)] bg-[var(--code-bg)]"
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

      {!loading && !error && events.length === 0 && (
        <p className="py-12 text-center text-sm text-[var(--text)]">No events yet.</p>
      )}

      {!loading && !error && events.length > 0 && filtered.length === 0 && (
        <p className="py-12 text-center text-sm text-[var(--text)]">No matches.</p>
      )}

      {!loading && !error && filtered.length > 0 && (
        <div className="flex flex-col gap-3">
          {filtered.map((event) => (
            <button
              key={event.id}
              type="button"
              onClick={() => navigate('/events/' + event.id)}
              className="rounded border border-[var(--border)] px-4 py-3 text-left hover:border-[var(--accent)]"
            >
              <h2 className="!m-0 !text-base">{event.name}</h2>
              <p className="text-sm text-[var(--text)]">{event.venue}</p>
              <p className="text-sm text-[var(--text)]">
                {dateFormatter.format(new Date(event.startsAt))}
              </p>
            </button>
          ))}
        </div>
      )}

      {!loading && !error && totalPages > 1 && (
        <div className="mt-6 flex items-center justify-center gap-4">
          <button
            type="button"
            disabled={page === 0}
            onClick={() => setPage((p) => p - 1)}
            className="rounded border border-[var(--border)] px-3 py-1 disabled:opacity-50"
          >
            Prev
          </button>
          <span className="text-sm text-[var(--text)]">
            Page {page + 1} of {totalPages}
          </span>
          <button
            type="button"
            disabled={page >= totalPages - 1}
            onClick={() => setPage((p) => p + 1)}
            className="rounded border border-[var(--border)] px-3 py-1 disabled:opacity-50"
          >
            Next
          </button>
        </div>
      )}
    </div>
  );
}
