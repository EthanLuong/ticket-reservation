import { Link, NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  `text-sm transition-colors ${
    isActive
      ? 'font-medium text-[var(--text-h)] underline decoration-[var(--accent)] decoration-2 underline-offset-8'
      : 'text-[var(--text)] hover:text-[var(--text-h)]'
  }`;

/**
 * Authenticated app frame: sticky header with wordmark, section nav, and
 * session controls. Pages render into <Outlet/> and no longer own logout.
 */
export default function AppShell() {
  const { email, logout } = useAuth();

  return (
    <div className="flex min-h-svh flex-col">
      <header className="sticky top-0 z-10 border-b border-[var(--border)] bg-[var(--bg)]/90 backdrop-blur">
        <div className="mx-auto flex max-w-3xl items-center gap-6 px-4 py-3">
          <Link to="/events" className="flex items-center gap-2 text-[var(--text-h)]">
            {/* ticket glyph — the product in eleven lines of SVG */}
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path
                d="M4 8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v1.5a2.5 2.5 0 0 0 0 5V16a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-1.5a2.5 2.5 0 0 0 0-5V8Z"
                stroke="var(--accent)"
                strokeWidth="1.8"
              />
              <path d="M14 6v12" stroke="var(--accent)" strokeWidth="1.8" strokeDasharray="2 2.5" />
            </svg>
            <span className="text-sm font-semibold tracking-tight">Ticket Reservation</span>
          </Link>

          <nav className="flex items-center gap-5">
            <NavLink to="/events" className={navLinkClass}>
              Events
            </NavLink>
            <NavLink to="/account" className={navLinkClass}>
              My reservations
            </NavLink>
          </nav>

          <div className="ml-auto flex items-center gap-3">
            {email && <span className="hidden text-xs text-[var(--text)] sm:inline">{email}</span>}
            <button
              type="button"
              onClick={logout}
              className="rounded border border-[var(--border)] px-2.5 py-1 text-xs hover:border-[var(--accent)]"
            >
              Log out
            </button>
          </div>
        </div>
      </header>

      <main className="flex-1">
        <Outlet />
      </main>
    </div>
  );
}
