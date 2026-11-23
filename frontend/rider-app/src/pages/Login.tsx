import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ApiError, login, requestOtp } from '../auth/api';
import GoogleSignIn from '../auth/GoogleSignIn';

/** Passwordless login: email -> one-time code -> signed in. */
export default function Login() {
  const navigate = useNavigate();
  const location = useLocation();
  const preset = (location.state as { email?: string; registered?: boolean } | null) ?? {};
  const [email, setEmail] = useState(preset.email ?? '');
  const [code, setCode] = useState('');
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [role, setRole] = useState<'RIDER' | 'DRIVER'>('RIDER'); // only used if Google creates a new account

  async function sendCode(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await requestOtp(email);
      setSent(true); // same screen whether or not the email exists: no account enumeration
    } catch {
      setError('Could not send a code. Please try again.');
    } finally {
      setBusy(false);
    }
  }

  async function verify(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email, code);
      navigate('/', { replace: true });
    } catch (err) {
      setError(
        err instanceof ApiError && err.status === 401
          ? 'That code is wrong or has expired. Request a new one.'
          : 'Login failed. Please try again.',
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card">
      <h2>Log in</h2>
      {preset.registered && !sent && <p className="ok">Account created. Request a code to log in.</p>}
      {!sent ? (
        <form onSubmit={sendCode}>
          <label>
            Email
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="email" />
          </label>
          {error && <p role="alert" className="error">{error}</p>}
          <button disabled={busy}>{busy ? 'Sending…' : 'Email me a code'}</button>
        </form>
      ) : (
        <form onSubmit={verify}>
          <p className="muted">If that account exists, a 6-digit code was sent to {email}. It expires in 5 minutes.</p>
          <label>
            Code
            <input
              inputMode="numeric"
              pattern="\d{6}"
              maxLength={6}
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
              required
              autoComplete="one-time-code"
              autoFocus
            />
          </label>
          {error && <p role="alert" className="error">{error}</p>}
          <button disabled={busy || code.length !== 6}>{busy ? 'Checking…' : 'Log in'}</button>
          <button type="button" className="link" onClick={() => { setSent(false); setCode(''); setError(null); }}>
            Use a different email or resend
          </button>
        </form>
      )}
      <div className="divider">or</div>
      <label>
        First time here? I am a
        <select value={role} onChange={(e) => setRole(e.target.value as 'RIDER' | 'DRIVER')}>
          <option value="RIDER">Rider</option>
          <option value="DRIVER">Driver</option>
        </select>
      </label>
      <GoogleSignIn role={role} />
      <p className="muted">
        New here? <Link to="/register">Create an account</Link>
      </p>
    </div>
  );
}
