import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError } from '../auth/api';
import {
  SERVICE_ICONS, SERVICE_LABELS, driverAction, formatMoney, listBookings, type Booking, type DriverAction, type ServiceType,
} from '../auth/bookings';
import {
  getProfile, goOffline, goOnline, saveProfile, type DriverProfile, type VehicleForm,
} from '../auth/drivers';
import { acceptOffer, myOffers, rejectOffer, type Offer } from '../auth/offers';
import { useLocationSharing, type LocationSource } from '../driver/useLocationSharing';
import type { LatLng } from '../map/geo';
import RouteMap from '../map/RouteMap';
import StatusPill from '../ui/StatusPill';
import TripProgress from '../ui/TripProgress';
import { secondsLeft } from '../ui/format';
import { Stops } from './Trips';

const ACTIVE = ['MATCHED', 'ARRIVED', 'STARTED'];
const NEXT_STEP: Record<string, { action: DriverAction; label: string }> = {
  MATCHED: { action: 'arrive', label: "I've arrived at pickup" },
  ARRIVED: { action: 'start', label: 'Start trip' },
  STARTED: { action: 'complete', label: 'Complete trip' },
};
const OFFER_WINDOW_SECONDS = 15;
// A starting spot for the simulated car, near MG Road, Bengaluru. Tap the map to move it.
const DEFAULT_START: LatLng = { lat: 12.977, lng: 77.6065 };
const VEHICLES = Object.keys(SERVICE_LABELS) as ServiceType[];

const ERRORS: Record<string, string> = {
  PLATE_ALREADY_REGISTERED: 'That plate number is already registered to another driver.',
  INVALID_REQUEST: 'Please check the plate number: letters and digits only, 4 to 15 characters.',
  VALIDATION_FAILED: 'Please fill in every field.',
};

function VehicleFormCard({ initial, onSaved, onCancel }: { initial: VehicleForm | null; onSaved: (p: DriverProfile) => void; onCancel?: () => void }) {
  const [vehicleType, setType] = useState<ServiceType>(initial?.vehicleType ?? 'CAR_MINI');
  const [plateNumber, setPlate] = useState(initial?.plateNumber ?? '');
  const [vehicleModel, setModel] = useState(initial?.vehicleModel ?? '');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onSaved(await saveProfile({ vehicleType, plateNumber, vehicleModel }));
    } catch (err) {
      setError((err instanceof ApiError && err.code && ERRORS[err.code]) || 'We could not save your vehicle. Please try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="card form">
      <h2>{initial ? 'Edit your vehicle' : 'Register your vehicle'}</h2>
      {!initial && <p className="page-sub">Tell us what you drive. You will only be offered rides for this vehicle type.</p>}
      <label className="field">
        Vehicle type
        <select value={vehicleType} onChange={(e) => setType(e.target.value as ServiceType)}>
          {VEHICLES.map((v) => <option key={v} value={v}>{SERVICE_ICONS[v]} {SERVICE_LABELS[v]}</option>)}
        </select>
      </label>
      <label className="field">
        Plate number
        <input value={plateNumber} onChange={(e) => setPlate(e.target.value)} required maxLength={20} placeholder="KA 01 AB 1234" />
      </label>
      <label className="field">
        Vehicle model
        <input value={vehicleModel} onChange={(e) => setModel(e.target.value)} required maxLength={60} placeholder="Maruti Swift" />
      </label>
      {error && <div role="alert" className="alert error">{error}</div>}
      <div className="row-actions">
        {onCancel && <button type="button" className="btn secondary" onClick={onCancel}>Cancel</button>}
        <button className="btn" disabled={busy}>{busy ? 'Saving…' : 'Save vehicle'}</button>
      </div>
    </form>
  );
}

function OfferCard({ offer, busy, onAccept, onReject }: { offer: Offer; busy: boolean; onAccept: () => void; onReject: () => void }) {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 500);
    return () => clearInterval(timer);
  }, []);
  const left = secondsLeft(offer.expiresAt, now);
  const pct = Math.min(100, (left / OFFER_WINDOW_SECONDS) * 100);

  return (
    <article className="card offer" aria-label="New ride offer">
      <div className="trip-head">
        <span className="v-icon" aria-hidden="true">{SERVICE_ICONS[offer.serviceType]}</span>
        <div className="grow">
          <div className="name">New ride request</div>
          <div className="hint">{offer.pickupDistanceKm} km to pickup · {offer.distanceKm} km trip · ~{offer.estimatedMinutes} min</div>
        </div>
        <strong>{formatMoney(offer.estimatedFare, offer.currency)}</strong>
      </div>
      <div className="stops-mini">
        <span className="m-dot" aria-hidden="true" /><span>{offer.pickup.address ?? 'Pickup'}</span>
        <span className="m-line" aria-hidden="true" /><span />
        <span className="m-dot drop" aria-hidden="true" /><span>{offer.drop.address ?? 'Drop'}</span>
      </div>
      <div className={`countdown ${left <= 5 ? 'low' : ''}`} role="timer" aria-label={`${left} seconds left to answer`}>
        <span style={{ width: `${pct}%` }} />
      </div>
      <div className="hint">{left > 0 ? `${left}s left to answer` : 'Time is up'}</div>
      <div className="row-actions">
        <button className="btn secondary" disabled={busy} onClick={onReject}>Decline</button>
        <button className="btn" disabled={busy || left === 0} onClick={onAccept}>Accept ride</button>
      </div>
    </article>
  );
}

/** Driver console: register a vehicle, go online, receive ride offers, and drive the trip to completion. */
export default function Driver() {
  const [profile, setProfile] = useState<DriverProfile | null | undefined>(undefined); // undefined = still loading
  const [editing, setEditing] = useState(false);
  const [offers, setOffers] = useState<Offer[]>([]);
  const [active, setActive] = useState<Booking | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [source, setSource] = useState<LocationSource>('simulator');
  const [start, setStart] = useState<LatLng>(DEFAULT_START);
  const profileRef = useRef(profile);
  profileRef.current = profile;

  const online = profile?.status === 'ONLINE';

  useEffect(() => {
    getProfile().then(setProfile).catch(() => setMessage('We could not load your profile.'));
  }, []);

  // where the simulated car heads: to the pickup once a ride is accepted, then to the drop once the trip starts
  const destination: LatLng | null = !active ? null
    : active.status === 'MATCHED' ? active.pickup
    : active.status === 'STARTED' ? active.drop
    : null;

  const { position, error: locationError, lastPingAt } = useLocationSharing({
    enabled: online,
    source,
    start,
    destination,
    onServerOffline: () => setProfile((p) => (p ? { ...p, status: 'OFFLINE' } : p)),
  });

  const refresh = useCallback(async () => {
    if (profileRef.current?.status !== 'ONLINE') return;
    try {
      const [live, mine] = await Promise.all([myOffers(), listBookings({ limit: 10 })]);
      setOffers(live);
      setActive(mine.items.find((b) => ACTIVE.includes(b.status)) ?? null);
    } catch {
      /* a failed poll just means we look again in a moment */
    }
  }, []);

  useEffect(() => {
    if (!online) {
      setOffers([]);
      return;
    }
    void refresh();
    const timer = setInterval(() => void refresh(), 2000);
    return () => clearInterval(timer);
  }, [online, refresh]);

  async function toggleOnline() {
    if (!profile) return;
    setBusy(true);
    setMessage(null);
    try {
      setProfile(online ? await goOffline() : await goOnline());
    } catch {
      setMessage('That did not work. Please try again.');
    } finally {
      setBusy(false);
    }
  }

  async function answer(offer: Offer, action: 'accept' | 'reject') {
    setBusy(true);
    setMessage(null);
    try {
      if (action === 'accept') await acceptOffer(offer.id);
      else await rejectOffer(offer.id);
    } catch (err) {
      setMessage(
        err instanceof ApiError && err.status === 409
          ? 'That offer is no longer available: it expired, or the rider cancelled.'
          : 'That did not work. Please try again.',
      );
    } finally {
      setBusy(false);
      await refresh();
    }
  }

  async function step(booking: Booking, action: DriverAction) {
    setBusy(true);
    setMessage(null);
    try {
      await driverAction(booking.id, action);
    } catch {
      setMessage('That did not work. Please try again.');
    } finally {
      setBusy(false);
      await refresh();
    }
  }

  if (profile === undefined) {
    return <div className="container"><div className="skeleton" style={{ height: 180 }} /></div>;
  }

  if (profile === null || editing) {
    return (
      <div className="container">
        <h1 className="page-title">Driver console</h1>
        <VehicleFormCard
          initial={profile}
          onSaved={(p) => { setProfile(p); setEditing(false); }}
          onCancel={profile ? () => setEditing(false) : undefined}
        />
      </div>
    );
  }

  const offer = offers[0];
  const stale = online && lastPingAt !== null && Date.now() - lastPingAt > 15_000;

  return (
    <div className="container">
      <div>
        <h1 className="page-title">Driver console</h1>
        <p className="page-sub">
          {SERVICE_ICONS[profile.vehicleType]} {SERVICE_LABELS[profile.vehicleType]} · {profile.vehicleModel} · {profile.plateNumber}{' '}
          <button className="btn ghost sm" onClick={() => setEditing(true)}>Edit</button>
        </p>
      </div>

      <div className="card">
        <div className="trip-head">
          <div className="grow">
            <div className="name">{online ? 'You are online' : 'You are offline'}</div>
            <div className="hint">{online ? (active ? 'On a trip' : 'Waiting for ride offers') : 'Go online to receive rides.'}</div>
          </div>
          <span className="switch">
            <button role="switch" aria-checked={online} aria-label="Online" disabled={busy || (online && active !== null)} onClick={toggleOnline} />
          </span>
        </div>
        {online && !active && <div className="searching">Looking for rides near you</div>}
      </div>

      {message && <div role="alert" className="alert error">{message}</div>}

      {online && offer && !active && (
        <OfferCard offer={offer} busy={busy} onAccept={() => answer(offer, 'accept')} onReject={() => answer(offer, 'reject')} />
      )}

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
          <TripProgress status={active.status} />
          <div className="trip-foot">
            <span className="price">{formatMoney(active.estimatedFare, active.currency)}</span>
          </div>
          <button className="btn lg block" disabled={busy} onClick={() => step(active, NEXT_STEP[active.status].action)}>
            {NEXT_STEP[active.status].label}
          </button>
        </article>
      )}

      {online && (
        <div className="card">
          <div className="section-title"><h3>Your location</h3></div>
          <div className="segmented" role="group" aria-label="Where your location comes from">
            <button type="button" className={source === 'simulator' ? 'on' : ''} onClick={() => setSource('simulator')}>🧪 Simulator</button>
            <button type="button" className={source === 'gps' ? 'on' : ''} onClick={() => setSource('gps')}>📍 This device</button>
          </div>
          <RouteMap
            interactive={source === 'simulator' && !active}
            target="driver"
            driver={position}
            pickup={active ? active.pickup : null}
            drop={active ? active.drop : null}
            onPick={(kind, p) => kind === 'driver' && setStart(p)}
            height={240}
            label="Your position"
          />
          <p className="hint">
            {source === 'simulator'
              ? active ? 'The simulated car drives to the pickup, then to the drop, by itself.' : 'Tap the map to move the simulated car. It drives for you once you accept a ride.'
              : 'Your phone or computer GPS is shared with riders on your trip.'}
          </p>
          {locationError && <div role="alert" className="alert error">{locationError}</div>}
          {stale && <div role="alert" className="alert error">Your location has not been sent for a while.</div>}
        </div>
      )}
    </div>
  );
}
