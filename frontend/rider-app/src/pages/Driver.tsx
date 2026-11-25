import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../auth/api';
import {
  SERVICE_ICONS, SERVICE_LABELS, driverAction, formatMoney, listBookings, openRequests,
  type Booking, type DriverAction,
} from '../auth/bookings';
import RouteMap from '../map/RouteMap';
import { timeAgo } from '../ui/format';
import StatusPill from '../ui/StatusPill';
import TripProgress from '../ui/TripProgress';
import { Stops } from './Trips';

const ACTIVE = ['MATCHED', 'ARRIVED', 'STARTED'];
const NEXT_STEP: Record<string, { action: DriverAction; label: string }> = {
  MATCHED: { action: 'arrive', label: "I've arrived at pickup" },
  ARRIVED: { action: 'start', label: 'Start trip' },
  STARTED: { action: 'complete', label: 'Complete trip' },
};

/** Driver console: see open ride requests, take one, and walk it through to completion. */
export default function Driver() {
  const [open, setOpen] = useState<Booking[]>([]);
  const [active, setActive] = useState<Booking | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [requests, mine] = await Promise.all([openRequests(), listBookings({ limit: 10 })]);
      setOpen(requests);
      setActive(mine.items.find((b) => ACTIVE.includes(b.status)) ?? null);
      setLoaded(true);
    } catch {
      setMessage('We could not load requests.');
    }
  }, []);

  useEffect(() => {
    void refresh();
    const timer = setInterval(() => void refresh(), 4000);
    return () => clearInterval(timer);
  }, [refresh]);

  async function act(id: string, action: DriverAction) {
    setMessage(null);
    setBusyId(id);
    try {
      await driverAction(id, action);
    } catch (err) {
      // 409 on accept means another driver got there first: that is normal, not a failure of the app
      setMessage(
        err instanceof ApiError && err.status === 409
          ? 'Too late: another driver took that ride, or the rider cancelled it.'
          : 'That did not work. Please try again.',
      );
    } finally {
      setBusyId(null);
    }
    await refresh();
  }

  return (
    <div className="container">
      <div>
        <h1 className="page-title">Driver console</h1>
        <p className="page-sub">{active ? 'You have a trip in progress.' : <span className="live">Looking for ride requests</span>}</p>
      </div>

      {message && <div role="alert" className="alert error">{message}</div>}

      {!loaded && <div className="skeleton" style={{ height: 160 }} />}

      {active && (
        <article className="card trip" aria-label="Current trip">
          <div className="trip-head">
            <span className="v-icon" aria-hidden="true">{SERVICE_ICONS[active.serviceType]}</span>
            <div className="grow">
              <div className="name">{SERVICE_LABELS[active.serviceType]}</div>
              <div className="hint">{active.distanceKm} km · ~{active.estimatedMinutes} min</div>
            </div>
            <StatusPill status={active.status} />
          </div>
          <Stops trip={active} />
          <RouteMap pickup={active.pickup} drop={active.drop} height={170} label="Route to drive" />
          <TripProgress status={active.status} />
          <div className="trip-foot">
            <span className="price">{formatMoney(active.estimatedFare, active.currency)}</span>
          </div>
          <button className="btn lg block" disabled={busyId === active.id} onClick={() => act(active.id, NEXT_STEP[active.status].action)}>
            {NEXT_STEP[active.status].label}
          </button>
        </article>
      )}

      {!active && loaded && (
        <>
          <div className="section-title">
            <h2>Open requests</h2>
            <span className="hint">{open.length} waiting</span>
          </div>
          {open.length === 0 && (
            <div className="card empty">
              <span className="big" aria-hidden="true">🛰️</span>
              <strong>No requests right now</strong>
              <span>New rides appear here automatically.</span>
            </div>
          )}
          {open.map((b) => (
            <article key={b.id} className="card trip" aria-label="Ride request">
              <div className="trip-head">
                <span className="v-icon" aria-hidden="true">{SERVICE_ICONS[b.serviceType]}</span>
                <div className="grow">
                  <div className="name">{SERVICE_LABELS[b.serviceType]}</div>
                  <div className="hint">{timeAgo(b.createdAt)} · {b.distanceKm} km · ~{b.estimatedMinutes} min</div>
                </div>
                <strong>{formatMoney(b.estimatedFare, b.currency)}</strong>
              </div>
              <Stops trip={b} />
              <button className="btn block" disabled={busyId === b.id} onClick={() => act(b.id, 'accept')}>
                {busyId === b.id ? 'Accepting…' : 'Accept ride'}
              </button>
            </article>
          ))}
        </>
      )}
    </div>
  );
}
