import { Link, NavLink, Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import { logout } from './auth/api';
import { useAuth } from './auth/AuthContext';
import Book from './pages/Book';
import Driver from './pages/Driver';
import Home from './pages/Home';
import Login from './pages/Login';
import Register from './pages/Register';
import Trips from './pages/Trips';
import { initialsOf, roleLabel } from './ui/format';

function Protected({ children }: { children: React.ReactElement }) {
  const { ready, signedIn } = useAuth();
  if (!ready) {
    return (
      <div className="container">
        <div className="skeleton" style={{ height: 90 }} />
        <div className="skeleton" style={{ height: 160 }} />
      </div>
    );
  }
  return signedIn ? children : <Navigate to="/login" replace />;
}

function Header() {
  const { signedIn, profile } = useAuth();
  const navigate = useNavigate();

  async function signOut() {
    await logout();
    navigate('/login', { replace: true });
  }

  return (
    <header className="header">
      <div className="header-inner">
        <Link to="/" className="brand" aria-label="Ride Booking App home">
          <span className="brand-mark" aria-hidden="true">🚕</span>
          <span>RideBooking</span>
        </Link>
        {signedIn && profile && (
          <>
            <nav className="nav" aria-label="Main">
              <NavLink to="/" end>Home</NavLink>
              {profile.role === 'RIDER' && <NavLink to="/book">Book</NavLink>}
              {profile.role === 'DRIVER' && <NavLink to="/driver">Drive</NavLink>}
              <NavLink to="/trips">Trips</NavLink>
            </nav>
            <div className="userbox">
              <span className="avatar" title={`${profile.email} · ${roleLabel(profile.role)}`}>{initialsOf(profile.email)}</span>
              <button className="btn ghost sm" onClick={signOut}>Log out</button>
            </div>
          </>
        )}
      </div>
    </header>
  );
}

export default function App() {
  return (
    <div className="shell">
      <Header />
      <Routes>
        <Route path="/register" element={<Register />} />
        <Route path="/login" element={<Login />} />
        <Route path="/" element={<Protected><Home /></Protected>} />
        <Route path="/book" element={<Protected><Book /></Protected>} />
        <Route path="/trips" element={<Protected><Trips /></Protected>} />
        <Route path="/driver" element={<Protected><Driver /></Protected>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </div>
  );
}
