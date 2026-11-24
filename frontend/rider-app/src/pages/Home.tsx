import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, type DeviceSession, type UserProfile } from '../auth/api';
import { describeDevice, initialsOf, roleLabel, timeAgo } from '../ui/format';

function greetingName(email: string): string {
  const first = email.split('@')[0].split(/[._-]+/)[0];
  return first.charAt(0).toUpperCase() + first.slice(1);
}

export default function Home() {
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
      setError('We could not load your account. Please refresh the page.');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function endDevice(id: string) {
    await api<void>(`/api/users/me/sessions/${id}`, { method: 'DELETE' });
    await load();
  }

  if (error) {
    return <div className="container"><div role="alert" className="alert error">{error}</div></div>;
  }
  if (!me) {
    return (
      <div className="container">
        <div className="skeleton" style={{ height: 96 }} />
        <div className="skeleton" style={{ height: 110 }} />
        <div className="skeleton" style={{ height: 180 }} />
      </div>
    );
  }

  const isRider = me.role === 'RIDER';
  const isDriver = me.role === 'DRIVER';

  return (
    <div className="container">
      <section className="card profile" aria-label="Your account">
        <span className="avatar large" aria-hidden="true">{initialsOf(me.email)}</span>
        <div>
          <h2>Hi, {greetingName(me.email)} 👋</h2>
          <div className="meta hint">{me.email}</div>
          <div style={{ marginTop: 6, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <span className="pill primary">{roleLabel(me.role)}</span>
            <span className="pill">Member since {new Date(me.createdAt).toLocaleDateString('en-GB', { month: 'short', year: 'numeric' })}</span>
          </div>
        </div>
      </section>

      <nav className="tiles" aria-label="Quick actions">
        {isRider && (
          <Link className="tile primary" to="/book">
            <span className="tile-icon" aria-hidden="true">🚕</span>
            <strong>Book a ride</strong>
            <span className="sub">Bike, auto or car</span>
          </Link>
        )}
        {isDriver && (
          <Link className="tile primary" to="/driver">
            <span className="tile-icon" aria-hidden="true">🧭</span>
            <strong>Driver console</strong>
            <span className="sub">See ride requests</span>
          </Link>
        )}
        <Link className="tile" to="/trips">
          <span className="tile-icon" aria-hidden="true">🧾</span>
          <strong>My trips</strong>
          <span className="sub">History and status</span>
        </Link>
      </nav>

      <div className="section-title">
        <h3>Where you are signed in</h3>
        <span className="hint">{devices.length} {devices.length === 1 ? 'device' : 'devices'}</span>
      </div>
      <ul className="list">
        {devices.map((d) => {
          const device = describeDevice(d.deviceName);
          return (
            <li key={d.id} className={`list-row ${d.current ? 'current' : ''}`}>
              <span className="device-icon" aria-hidden="true">{device.icon}</span>
              <div className="grow">
                <div className="title">
                  {device.label}
                  {d.current && <span className="pill ok">This device</span>}
                </div>
                <div className="meta">
                  {d.current ? 'Active now' : `Last active ${timeAgo(d.lastSeenAt)}`}
                  {d.ipAddress ? ` · ${d.ipAddress}` : ''}
                </div>
              </div>
              {!d.current && (
                <button className="btn danger-ghost sm" onClick={() => endDevice(d.id)} aria-label={`End session on ${device.label}`}>
                  Sign out
                </button>
              )}
            </li>
          );
        })}
      </ul>
      <p className="hint">If you do not recognise a device, sign it out. Signing out takes effect immediately.</p>
    </div>
  );
}
