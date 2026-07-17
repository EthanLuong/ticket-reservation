import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { api, toastFor } from '../lib/api';
import type { EventResponse, SeatResponse } from '../lib/types';
import SeatGrid from '../components/SeatGrid';
import HoldBanner from '../components/HoldBanner';
import { useToast } from '../components/Toast';

const dateFormatter = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

const POLL_MS = 5000;

interface ActiveHold {
  seatId: string;
  seatLabel: string;
  expiresAt: string;
}

export default function EventDetailPage() {
  const { id } = useParams();
  const { showToast } = useToast();

  const [event, setEvent] = useState<EventResponse | null>(null);
  const [eventLoading, setEventLoading] = useState(true);
  const [eventError, setEventError] = useState<string | null>(null);

  const [seats, setSeats] = useState<SeatResponse[]>([]);
  const [seatsLoading, setSeatsLoading] = useState(true);
  const [seatsError, setSeatsError] = useState<string | null>(null);

  const [hold, setHold] = useState<ActiveHold | null>(null);
  const [reserving, setReserving] = useState(false);

  const mountedRef = useRef(true);
  const idRef = useRef(id);
  useEffect(() => {
    mountedRef.current = true;
    idRef.current = id;
    return () => {
      mountedRef.current = false;
    };
  }, [id]);

  const loadEvent = useCallback(() => {
    if (!id) return Promise.resolve();
    const requestedId = id;
    setEventLoading(true);
    setEventError(null);
    return api
      .event(id)
      .then((res) => {
        if (!mountedRef.current || requestedId !== idRef.current) return;
        setEvent(res);
      })
      .catch((err) => {
        if (!mountedRef.current || requestedId !== idRef.current) return;
        setEventError(toastFor(err));
      })
      .finally(() => {
        if (!mountedRef.current || requestedId !== idRef.current) return;
        setEventLoading(false);
      });
  }, [id]);

  const loadSeats = useCallback(
    (opts?: { silent?: boolean }) => {
      if (!id) return Promise.resolve();
      const requestedId = id;
      if (!opts?.silent) {
        setSeatsLoading(true);
        setSeatsError(null);
      }
      return api
        .seats(id)
        .then((res) => {
          if (!mountedRef.current || requestedId !== idRef.current) return;
          setSeats(res);
          setSeatsError(null);
        })
        .catch((err) => {
          if (!mountedRef.current || requestedId !== idRef.current) return;
          // Background polls fail silently rather than clobbering a working grid
          // with an error state; only the initial load surfaces a blocking error.
          if (!opts?.silent) setSeatsError(toastFor(err));
        })
        .finally(() => {
          if (!mountedRef.current || requestedId !== idRef.current) return;
          if (!opts?.silent) setSeatsLoading(false);
        });
    },
    [id],
  );

  // Reset any stale hold if the route param changes (component instance is reused).
  useEffect(() => {
    setHold(null);
  }, [id]);

  useEffect(() => {
    loadEvent();
  }, [loadEvent]);

  useEffect(() => {
    loadSeats();
  }, [loadSeats]);

  // Poll seats every 5s while the tab is visible; pause while hidden.
  // A ref keeps the interval callback pointed at the latest loadSeats
  // (which itself closes over the current `id`) so nothing goes stale.
  const loadSeatsRef = useRef(loadSeats);
  useEffect(() => {
    loadSeatsRef.current = loadSeats;
  }, [loadSeats]);

  useEffect(() => {
    let intervalId: ReturnType<typeof setInterval> | null = null;

    function start() {
      if (intervalId !== null) return;
      intervalId = setInterval(() => {
        loadSeatsRef.current({ silent: true });
      }, POLL_MS);
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
        loadSeatsRef.current({ silent: true });
      }
    }

    if (document.visibilityState === 'visible') start();
    document.addEventListener('visibilitychange', handleVisibilityChange);

    return () => {
      stop();
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [id]);

  const handleSelectSeat = useCallback(
    async (seat: SeatResponse) => {
      setReserving(true);
      try {
        const reservation = await api.reserve(seat.id);
        if (!mountedRef.current) return;
        if (reservation.expiresAt) {
          setHold({
            seatId: reservation.seatId,
            seatLabel: seat.seatLabel,
            expiresAt: reservation.expiresAt,
          });
        } else {
          showToast('Seat held — see My Reservations.');
        }
        loadSeats({ silent: true });
      } catch (err) {
        if (!mountedRef.current) return;
        showToast(toastFor(err));
        // Grid was stale — refetch immediately so it reflects the contention.
        loadSeats({ silent: true });
      } finally {
        if (mountedRef.current) setReserving(false);
      }
    },
    [loadSeats, showToast],
  );

  const handleHoldExpire = useCallback(() => {
    setHold(null);
    loadSeats({ silent: true });
  }, [loadSeats]);

  if (!id) {
    return (
      <div className="mx-auto mt-16 max-w-3xl px-4">
        <p className="text-sm text-[var(--text)]">Invalid event.</p>
      </div>
    );
  }

  return (
    <div className="mx-auto mt-16 max-w-3xl px-4 pb-16">
      {eventLoading && (
        <div className="mb-6 h-20 animate-pulse rounded border border-[var(--border)] bg-[var(--code-bg)]" />
      )}

      {!eventLoading && eventError && (
        <div className="flex flex-col items-center gap-3 py-6">
          <p className="text-sm text-red-600">{eventError}</p>
          <button
            type="button"
            onClick={() => loadEvent()}
            className="rounded bg-[var(--accent)] px-4 py-2 text-white"
          >
            Retry
          </button>
        </div>
      )}

      {!eventLoading && !eventError && event && (
        <>
          <h1>{event.name}</h1>
          <p className="text-sm text-[var(--text)]">{event.venue}</p>
          <p className="mb-6 text-sm text-[var(--text)]">
            {dateFormatter.format(new Date(event.startsAt))}
          </p>
        </>
      )}

      {hold && (
        <HoldBanner
          seatLabel={hold.seatLabel}
          expiresAt={hold.expiresAt}
          onExpire={handleHoldExpire}
        />
      )}

      {seatsLoading && (
        <div className="grid grid-cols-5 gap-2 sm:grid-cols-8 md:grid-cols-10">
          {Array.from({ length: 20 }).map((_, i) => (
            <div
              key={i}
              className="h-8 animate-pulse rounded border border-[var(--border)] bg-[var(--code-bg)]"
            />
          ))}
        </div>
      )}

      {!seatsLoading && seatsError && (
        <div className="flex flex-col items-center gap-3 py-12">
          <p className="text-sm text-red-600">{seatsError}</p>
          <button
            type="button"
            onClick={() => loadSeats()}
            className="rounded bg-[var(--accent)] px-4 py-2 text-white"
          >
            Retry
          </button>
        </div>
      )}

      {!seatsLoading && !seatsError && seats.length === 0 && (
        <p className="py-12 text-center text-sm text-[var(--text)]">No seats for this event.</p>
      )}

      {!seatsLoading && !seatsError && seats.length > 0 && (
        <SeatGrid seats={seats} disabled={reserving} onSelect={handleSelectSeat} />
      )}
    </div>
  );
}
