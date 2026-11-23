import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError, register } from '../auth/api';
import GoogleSignIn from '../auth/GoogleSignIn';

const MESSAGES: Record<string, string> = {
  EMAIL_ALREADY_REGISTERED: 'That email is already registered. Try logging in.',
  VALIDATION_FAILED: 'Check the email and use a password of at least 10 characters.',
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
      setError((code && MESSAGES[code]) || 'Could not register. Please try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="card">
      <h2>Create account</h2>
      <label>
        Email
        <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="email" />
      </label>
      <label>
        Password (min 10 characters)
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
          minLength={10}
          maxLength={72}
          autoComplete="new-password"
        />
      </label>
      <label>
        I am a
        <select value={role} onChange={(e) => setRole(e.target.value as 'RIDER' | 'DRIVER')}>
          <option value="RIDER">Rider</option>
          <option value="DRIVER">Driver</option>
        </select>
      </label>
      {error && <p role="alert" className="error">{error}</p>}
      <button disabled={busy}>{busy ? 'Creating…' : 'Register'}</button>
      <div className="divider">or</div>
      <GoogleSignIn role={role} />
      <p className="muted">
        Already registered? <Link to="/login">Log in</Link>
      </p>
    </form>
  );
}
