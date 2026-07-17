import { BrowserRouter, Navigate, Route, Routes, useParams } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { RequireAuth } from './auth/RequireAuth';
import LoginPage from './pages/LoginPage';

// Placeholder — replaced by Task 4.
function EventsPage() {
  return <h1>Events</h1>;
}

// Placeholder — replaced by Task 5.
function EventDetailPage() {
  const { id } = useParams();
  return <h1>Event {id}</h1>;
}

// Placeholder — replaced by Task 6.
function AccountPage() {
  return <h1>Account</h1>;
}

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            path="/events"
            element={
              <RequireAuth>
                <EventsPage />
              </RequireAuth>
            }
          />
          <Route
            path="/events/:id"
            element={
              <RequireAuth>
                <EventDetailPage />
              </RequireAuth>
            }
          />
          <Route
            path="/account"
            element={
              <RequireAuth>
                <AccountPage />
              </RequireAuth>
            }
          />
          <Route path="/" element={<Navigate to="/events" replace />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}

export default App;
