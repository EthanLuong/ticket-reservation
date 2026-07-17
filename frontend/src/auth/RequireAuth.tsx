import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { claims } from '../lib/api';

export function RequireAuth({ children }: { children: ReactNode }) {
  const location = useLocation();
  if (!claims()) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }
  return children;
}
