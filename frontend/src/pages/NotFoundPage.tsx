import { useEffect } from 'react';
import { Link } from 'react-router-dom';

export default function NotFoundPage() {
  useEffect(() => {
    document.title = 'Page not found · Ticket Reservation';
  }, []);

  return (
    <div className="mx-auto mt-24 max-w-sm px-4 text-center">
      <h1 className="!text-4xl">404</h1>
      <p className="mb-6 text-sm text-[var(--text)]">This page doesn't exist.</p>
      <Link to="/events" className="text-sm font-medium text-[var(--accent)] underline">
        Browse events
      </Link>
    </div>
  );
}
