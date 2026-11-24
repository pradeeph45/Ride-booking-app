import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError, register } from '../auth/api';
import GoogleSignIn, { GOOGLE_ENABLED } from '../auth/GoogleSignIn';

const MESSAGES: Record<string, string> = {
  EMAIL_ALREADY_REGISTERED: 'That email is already registered. Try logging in instead.',
  VALIDATION_FAILED: 'Check the email address, and use a password of at least 10 characters.',
};

export default function Register() {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<'RIDER' | 'DRIVER'>('RIDER');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await register(email, password, role);
      navigate('/login', { state: { email, registered: true } });
    } catch (err) {
      const code = err instanceof ApiError ? err.code : undefined;
      setError((code && MESSAGES[code]) || 'We could not create your account. Please try again.');
    } finally {
      setBusy(false);
    }
  }

  const strength = password.length === 0 ? 0 : password.length < 10 ? 1 : password.length < 14 ? 2 : 3;

  return (
    <div className="container narrow">
      <div style={{ textAlign: 'center', padding: '12px 0 4px' }}>
        <h1 className="page-title">Create your account</h1>
        <p className="page-sub">Ride, or drive, in a couple of minutes.</p>
      </div>

      <form onSubmit={submit} className="card form">
        <div className="segmented" role="group" aria-label="I want to">
          <button type="button" className={role === 'RIDER' ? 'on' : ''} onClick={() => setRole('RIDER')}>🚕 Ride</button>
          <button type="button" className={role === 'DRIVER' ? 'on' : ''} onClick={() => setRole('DRIVER')}>🧭 Drive</button>
        </div>

        <label className="field">
          Email address
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="email" placeholder="you@example.com" />
        </label>

        <label className="field">
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            minLength={10}
            maxLength={72}
            autoComplete="new-password"
            placeholder="At least 10 characters"
          />
        </label>
        {password && (
          <p className="hint" aria-live="polite">
            {['', 'Too short: use at least 10 characters.', 'Good.', 'Strong.'][strength]}
          </p>
        )}

        {error && <div role="alert" className="alert error">{error}</div>}
        <button className="btn lg block" disabled={busy}>{busy ? 'Creating…' : 'Create account'}</button>

        {GOOGLE_ENABLED && (
          <>
            <div className="divider">or</div>
            <GoogleSignIn role={role} />
          </>
        )}
      </form>

      <p className="hint" style={{ textAlign: 'center' }}>
        Already registered? <Link to="/login">Log in</Link>
      </p>
    </div>
  );
}
