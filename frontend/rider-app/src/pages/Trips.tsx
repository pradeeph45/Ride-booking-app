import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  SERVICE_ICONS, SERVICE_LABELS, cancelBooking, formatMoney, listBookings, type Booking, type BookingStatus,
} from '../auth/bookings';
import { haversineKm } from '../map/geo';
import RouteMap from '../map/RouteMap';
import { useLiveDriver } from '../tracking/useLiveDriver';
import { timeAgo } from '../ui/format';
import StatusPill from '../ui/StatusPill';
import TripProgress from '../ui/TripProgress';

const CANCELLABLE: BookingStatus[] = ['REQUESTED', 'MATCHED', 'ARRIVED'];
const TERMINAL: BookingStatus[] = ['COMPLETED', 'CANCELLED'];
const FILTERS: { label: string; status?: BookingStatus }[] = [
  { label: 'All' },
  { label: 'Completed', status: 'COMPLETED' },
  { label: 'Cancelled', status: 'CANCELLED' },
];

export function Stops({ trip }: { trip: Booking }) {
  const name = (p: Booking['pickup']) => p.address ?? `${p.lat.toFixed(4)}, ${p.lng.toFixed(4)}`;
  return (
    <div className="stops-mini">
      <span className="m-dot" aria-hidden="true" /><span>{name(trip.pickup)}</span>
      <span className="m-line" aria-hidden="true" /><span />
      <span className="m-dot drop" aria-hidden="true" /><span>{name(trip.drop)}</span>
    </div>
  );
}

/**
 * The map for a trip that is not finished. Once a driver is assigned it follows them live: the car moves toward the
 * pickup, then along the trip. Before that it simply shows the route while we look for a driver.
 */
function LiveTripMap({ trip }: { trip: Booking }) {
  const following = ['MATCHED', 'ARRIVED', 'STARTED'].includes(trip.status);
  const { position, connected } = useLiveDriver(trip.id, following);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 5000);
    return () => clearInterval(timer);
  }, []);

  const heading = trip.status === 'STARTED' ? trip.drop : trip.pickup;
  const km = position ? haversineKm(position, heading) : null;
  const stale = position !== null && position.ts > 0 && now - position.ts > 30_000; // no update for 30 s

  return (
    <>
      <RouteMap pickup={trip.pickup} drop={trip.drop} driver={stale ? null : position} height={200} label={following ? 'Live driver location' : 'Trip route'} />
      {!following && trip.status === 'REQUESTED' && <div className="searching">Finding a driver near you…</div>}
      {following && (
        <div className="live-status" aria-live="polite">
          <span>
            {stale ? 'Driver location unavailable right now'
              : km === null ? 'Waiting for your driver’s location…'
              : trip.status === 'STARTED' ? `${km.toFixed(1)} km to your destination`
              : trip.status === 'ARRIVED' ? 'Your driver is at the pickup'
              : `Your driver is ${km.toFixed(1)} km away`}
          </span>
          <span className={`pill ${connected ? 'ok' : ''}`}>{connected ? 'Live' : 'Connecting…'}</span>
        </div>
      )}
    </>
  );
}

/** Ride history for the signed-in user, newest first. The first page refreshes while you look at it. */
export default function Trips() {
  const [filter, setFilter] = useState<BookingStatus | undefined>(undefined);
  const [trips, setTrips] = useState<Booking[]>([]);
  const [next, setNext] = useState<string | null>(null);
  const [extraPages, setExtraPages] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);

  const loadFirst = useCallback(async () => {
    try {
      const page = await listBookings({ status: filter });
      setTrips(page.items);
      setNext(page.nextCursor);
      setExtraPages(false);
      setLoaded(true);
      setError(null);
    } catch {
      setError('We could not load your trips.');
    }
  }, [filter]);

  useEffect(() => {
    setLoaded(false);
    void loadFirst();
    // keep the first page live (a driver may accept your ride) unless the user has paged further down
    const timer = setInterval(() => { if (!extraPages) void loadFirst(); }, 5000);
    return () => clearInterval(timer);
  }, [loadFirst, extraPages]);

  async function loadMore() {
    if (!next) return;
    const page = await listBookings({ cursor: next, status: filter });
    setTrips((t) => [...t, ...page.items]);
    setNext(page.nextCursor);
    setExtraPages(true);
  }

  async function cancel(id: string) {
    try {
      await cancelBooking(id);
    } catch {
      setError('That trip can no longer be cancelled.');
    }
    await loadFirst();
  }

  return (
    <div className="container">
      <div>
        <h1 className="page-title">My trips</h1>
        <p className="page-sub">Your rides, newest first.</p>
      </div>

      <div className="chips" role="group" aria-label="Filter trips">
        {FILTERS.map((f) => (
          <button key={f.label} className={`chip ${filter === f.status ? 'on' : ''}`} onClick={() => setFilter(f.status)}>{f.label}</button>
        ))}
      </div>

      {error && <div role="alert" className="alert error">{error}</div>}

      {!loaded && !error && (
        <>
          <div className="skeleton" style={{ height: 150 }} />
          <div className="skeleton" style={{ height: 150 }} />
        </>
      )}

      {loaded && trips.length === 0 && (
        <div className="card empty">
          <span className="big" aria-hidden="true">🧾</span>
          <strong>{filter ? 'No trips match this filter' : 'No trips yet'}</strong>
          <span>Your rides will show up here.</span>
          {!filter && <Link className="btn" to="/book">Book your first ride</Link>}
        </div>
      )}

      {trips.map((t) => (
        <article key={t.id} className="card trip" aria-label={`${SERVICE_LABELS[t.serviceType]} trip`}>
          <div className="trip-head">
            <span className="v-icon" aria-hidden="true">{SERVICE_ICONS[t.serviceType]}</span>
            <div className="grow">
              <div className="name">{SERVICE_LABELS[t.serviceType]}</div>
              <div className="hint">{timeAgo(t.createdAt)} · {t.distanceKm} km</div>
            </div>
            <StatusPill status={t.status} />
          </div>

          <Stops trip={t} />
          {!TERMINAL.includes(t.status) && <LiveTripMap trip={t} />}

          {t.status !== 'CANCELLED' && <TripProgress status={t.status} />}
          {t.status === 'MATCHED' && <div className="alert info">🚗 A driver is on the way to your pickup.</div>}
          {t.status === 'ARRIVED' && <div className="alert ok">📍 Your driver has arrived.</div>}

          <div className="trip-foot">
            <span className="price">
              {formatMoney(t.finalFare ?? t.estimatedFare, t.currency)}
              {t.finalFare === null && <span className="hint"> (estimate)</span>}
            </span>
            {CANCELLABLE.includes(t.status) && (
              <button className="btn danger-ghost sm" onClick={() => cancel(t.id)}>Cancel trip</button>
            )}
          </div>
        </article>
      ))}

      {next && <button className="btn secondary block" onClick={loadMore}>Load older trips</button>}
    </div>
  );
}
