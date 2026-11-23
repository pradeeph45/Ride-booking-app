import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, logout, type DeviceSession, type UserProfile } from '../auth/api';

export default function Home() {
  const navigate = useNavigate();
  const [me, setMe] = useState<UserProfile | null>(null);
  const [devices, setDevices] = useState<DeviceSession[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [profile, sessions] = await Promise.all([
        api<UserProfile>('/api/users/me'),
        api<DeviceSession[]>('/api/users/me/sessions'),
      ]);
      setMe(profile);
      setDevices(sessions);
    } catch {
      setError('Could not load your account.');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function endDevice(id: string) {
    await api<void>(`/api/users/me/sessions/${id}`, { method: 'DELETE' });
    await load();
  }

  async function signOut() {
    await logout();
    navigate('/login', { replace: true });
  }

  if (error) return <p role="alert" className="error center">{error}</p>;
  if (!me) return <p className="muted center">Loading…</p>;

  return (
    <div className="card">
      <h2>Welcome</h2>
      <dl>
        <dt>Email</dt>
        <dd>{me.email}</dd>
        <dt>Role</dt>
        <dd>{me.role}</dd>
        <dt>Member since</dt>
        <dd>{new Date(me.createdAt).toLocaleString()}</dd>
      </dl>

      <h3>Your devices</h3>
      <ul className="devices">
        {devices.map((d) => (
          <li key={d.id}>
            <div>
              <strong>{d.deviceName ?? 'Unknown device'}</strong> {d.current && <span className="badge">this device</span>}
              <div className="muted">
                {d.ipAddress ?? 'unknown IP'} · last active {new Date(d.lastSeenAt).toLocaleString()}
              </div>
            </div>
            {!d.current && <button className="link" onClick={() => endDevice(d.id)}>End session</button>}
          </li>
        ))}
      </ul>

      <button onClick={signOut}>Log out</button>
    </div>
  );
}
