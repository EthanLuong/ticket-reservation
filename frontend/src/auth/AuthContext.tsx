import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, claims, clearToken, setToken } from '../lib/api';

interface AuthContextValue {
  email: string | null;
  login(email: string, password: string): Promise<void>;
  register(email: string, password: string, displayName: string): Promise<void>;
  logout(): void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [email, setEmail] = useState<string | null>(() => claims()?.email ?? null);
  const navigate = useNavigate();

  const login = useCallback(async (email: string, password: string) => {
    const res = await api.login(email, password);
    setToken(res.accessToken);
    setEmail(claims()?.email ?? null);
  }, []);

  const register = useCallback(async (email: string, password: string, displayName: string) => {
    const res = await api.register(email, password, displayName);
    setToken(res.accessToken);
    setEmail(claims()?.email ?? null);
  }, []);

  const logout = useCallback(() => {
    clearToken();
    setEmail(null);
    navigate('/login');
  }, [navigate]);

  return <AuthContext.Provider value={{ email, login, register, logout }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within an AuthProvider');
  return ctx;
}
