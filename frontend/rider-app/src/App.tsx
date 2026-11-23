import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import Home from './pages/Home';
import Login from './pages/Login';
import Register from './pages/Register';

function Protected({ children }: { children: React.ReactElement }) {
  const { ready, signedIn } = useAuth();
  if (!ready) return <p className="muted center">Loading…</p>;
  return signedIn ? children : <Navigate to="/login" replace />;
}

export default function App() {
  return (
    <main>
      <h1>Ride Booking App</h1>
      <Routes>
        <Route path="/register" element={<Register />} />
        <Route path="/login" element={<Login />} />
        <Route path="/" element={<Protected><Home /></Protected>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </main>
  );
}
