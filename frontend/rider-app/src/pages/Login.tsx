import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ApiError, login, requestOtp } from '../auth/api';
import GoogleSignIn, { GOOGLE_ENABLED } from '../auth/GoogleSignIn';

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
      setError('We could not send a code. Please try again.');
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
          ? 'That code is wrong or has expired. Go back and request a new one.'
          : 'Login failed. Please try again.',
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="container narrow">
      <div style={{ textAlign: 'center', padding: '12px 0 4px' }}>
        <h1 className="page-title">{sent ? 'Check your email' : 'Welcome back'}</h1>
        <p className="page-sub">{sent ? `We sent a 6-digit code to ${email}` : 'Log in with a one-time code. No password needed.'}</p>
      </div>

      <div className="card">
        {preset.registered && !sent && <div className="alert ok">🎉 Account created. Enter your email to get a login code.</div>}

        {!sent ? (
          <form onSubmit={sendCode} className="form">
            <label className="field">
              Email address
              <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="email" placeholder="you@example.com" autoFocus />
            </label>
            {error && <div role="alert" className="alert error">{error}</div>}
            <button className="btn lg block" disabled={busy}>{busy ? 'Sending…' : 'Email me a code'}</button>
          </form>
        ) : (
          <form onSubmit={verify} className="form">
            <label className="field">
              Login code
              <input
                className="otp-input"
                inputMode="numeric"
                pattern="\d{6}"
                maxLength={6}
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                required
                autoComplete="one-time-code"
                autoFocus
                placeholder="······"
              />
            </label>
            <p className="hint">The code expires in 5 minutes and works once.</p>
            {error && <div role="alert" className="alert error">{error}</div>}
            <button className="btn lg block" disabled={busy || code.length !== 6}>{busy ? 'Checking…' : 'Log in'}</button>
            <button type="button" className="btn ghost block" onClick={() => { setSent(false); setCode(''); setError(null); }}>
              Use a different email or resend
            </button>
          </form>
        )}

        {!sent && GOOGLE_ENABLED && (
          <>
            <div className="divider">or continue with</div>
            <div className="segmented" role="group" aria-label="If this is your first time with Google, I am a">
              <button type="button" className={role === 'RIDER' ? 'on' : ''} onClick={() => setRole('RIDER')}>🚕 Rider</button>
              <button type="button" className={role === 'DRIVER' ? 'on' : ''} onClick={() => setRole('DRIVER')}>🧭 Driver</button>
            </div>
            <GoogleSignIn role={role} />
            <p className="hint" style={{ textAlign: 'center' }}>The choice above only applies when Google creates a new account.</p>
          </>
        )}
      </div>

      <p className="hint" style={{ textAlign: 'center' }}>
        New here? <Link to="/register">Create an account</Link>
      </p>
    </div>
  );
}
