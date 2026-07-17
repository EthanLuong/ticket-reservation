import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { toastFor } from '../lib/api';

type Tab = 'signin' | 'register';

export default function LoginPage() {
  const [tab, setTab] = useState<Tab>('signin');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { login, register } = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    document.title = 'Sign in · Ticket Reservation';
  }, []);

  function selectTab(next: Tab) {
    setTab(next);
    setError(null);
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setPending(true);
    try {
      if (tab === 'signin') {
        await login(email, password);
      } else {
        await register(email, password, displayName);
      }
      navigate('/events');
    } catch (err) {
      setError(toastFor(err));
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="mx-auto mt-16 max-w-sm px-4">
      <div className="mb-6 flex border-b border-[var(--border)]">
        <button
          type="button"
          className={`flex-1 py-2 text-sm font-medium ${tab === 'signin' ? 'border-b-2 border-[var(--accent)] text-[var(--text-h)]' : 'text-[var(--text)]'}`}
          onClick={() => selectTab('signin')}
        >
          Sign in
        </button>
        <button
          type="button"
          className={`flex-1 py-2 text-sm font-medium ${tab === 'register' ? 'border-b-2 border-[var(--accent)] text-[var(--text-h)]' : 'text-[var(--text)]'}`}
          onClick={() => selectTab('register')}
        >
          Create account
        </button>
      </div>

      <form onSubmit={handleSubmit} className="flex flex-col gap-3">
        <input
          type="email"
          required
          autoComplete="email"
          placeholder="Email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="rounded border border-[var(--border)] bg-transparent px-3 py-2"
        />

        {tab === 'register' && (
          <input
            type="text"
            required
            autoComplete="name"
            placeholder="Display name"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            className="rounded border border-[var(--border)] bg-transparent px-3 py-2"
          />
        )}

        <input
          type="password"
          required
          autoComplete={tab === 'signin' ? 'current-password' : 'new-password'}
          placeholder="Password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="rounded border border-[var(--border)] bg-transparent px-3 py-2"
        />

        {error && <p className="text-sm text-red-600">{error}</p>}

        <button
          type="submit"
          disabled={pending}
          className="rounded bg-[var(--accent)] py-2 text-white disabled:opacity-50"
        >
          {pending ? 'Please wait…' : tab === 'signin' ? 'Sign in' : 'Create account'}
        </button>
      </form>
    </div>
  );
}
