import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '../auth/api';
import { RetryKey } from '../auth/idempotency';
import {
  SERVICE_ICONS, SERVICE_LABELS, SERVICE_TAGLINES, createBooking, estimateFares, formatMoney,
  type FareQuote, type Place, type ServiceType,
} from '../auth/bookings';
import { geocoder, type PlaceResult } from '../map/geocode';
import { isValidLatLng, roundCoord, type LatLng } from '../map/geo';
import RouteMap, { type PinKind } from '../map/RouteMap';

// Bengaluru sample points so fares can be tried straight away; tap the map to choose your own.
const DEFAULT_PICKUP = { lat: '12.9756', lng: '77.6050', address: 'MG Road' };
const DEFAULT_DROP = { lat: '12.9352', lng: '77.6245', address: 'Koramangala' };

type Fields = { lat: string; lng: string; address: string };

const toPlace = (f: Fields): Place => ({ lat: Number(f.lat), lng: Number(f.lng), address: f.address || undefined });
const toLatLng = (f: Fields): LatLng | null =>
  isValidLatLng(Number(f.lat), Number(f.lng)) && f.lat !== '' && f.lng !== '' ? { lat: Number(f.lat), lng: Number(f.lng) } : null;
const fieldsFrom = (p: LatLng, address: string): Fields => ({ lat: String(roundCoord(p.lat)), lng: String(roundCoord(p.lng)), address });

const MESSAGES: Record<string, string> = {
  INVALID_REQUEST: 'Pickup and drop are too close together, or the location is invalid.',
  VALIDATION_FAILED: 'Please check the locations.',
};

interface StopProps {
  kind: PinKind;
  label: string;
  value: Fields;
  active: boolean;
  onFocusTarget: () => void;
  onChange: (f: Fields) => void;
  onSelect: (r: PlaceResult) => void;
  onUseMyLocation?: () => void;
}

function StopFields({ kind, label, value, active, onFocusTarget, onChange, onSelect, onUseMyLocation }: StopProps) {
  const [results, setResults] = useState<PlaceResult[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  const set = (key: keyof Fields) => (e: React.ChangeEvent<HTMLInputElement>) => onChange({ ...value, [key]: e.target.value });

  async function find() {
    setSearching(true);
    setSearchError(null);
    try {
      const found = await geocoder.search(value.address);
      setResults(found);
      if (found.length === 0) setSearchError('No places found. Try a landmark or street name, or tap the map.');
    } catch {
      setResults(null);
      setSearchError('Address search is unavailable right now. You can still tap the map.');
    } finally {
      setSearching(false);
    }
  }

  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter') {
      e.preventDefault(); // Enter searches the address; it must not submit the fare form
      void find();
    }
  }

  return (
    <div className={`stop-fields ${active ? 'active' : ''}`}>
      <label className="field">
        {label}
        <span className="search-row">
          <input
            value={value.address}
            onChange={(e) => { set('address')(e); setResults(null); }}
            onFocus={onFocusTarget}
            onKeyDown={onKeyDown}
            maxLength={255}
            placeholder="Search an address or tap the map"
            required
            aria-label={`${label} address`}
          />
          <button type="button" className="btn secondary sm" onClick={find} disabled={searching || value.address.trim().length < 3}>
            {searching ? '…' : 'Find'}
          </button>
        </span>
      </label>

      {onUseMyLocation && kind === 'pickup' && (
        <button type="button" className="btn ghost sm" onClick={onUseMyLocation}>📍 Use my current location</button>
      )}
      {searchError && <p className="hint" role="status">{searchError}</p>}
      {results && results.length > 0 && (
        <ul className="results" aria-label={`${label} suggestions`}>
          {results.map((r) => (
            <li key={`${r.lat},${r.lng}`}>
              <button type="button" onClick={() => { onSelect(r); setResults(null); }}>
                <span aria-hidden="true">📌</span> {r.address}
              </button>
            </li>
          ))}
        </ul>
      )}

      <details className="coords">
        <summary>Edit coordinates</summary>
        <div className="row">
          <label className="field">Latitude<input type="number" step="any" min={-90} max={90} value={value.lat} onChange={set('lat')} required /></label>
          <label className="field">Longitude<input type="number" step="any" min={-180} max={180} value={value.lng} onChange={set('lng')} required /></label>
        </div>
      </details>
    </div>
  );
}

export default function Book() {
  const navigate = useNavigate();
  const [pickup, setPickup] = useState<Fields>(DEFAULT_PICKUP);
  const [drop, setDrop] = useState<Fields>(DEFAULT_DROP);
  const [target, setTarget] = useState<PinKind>('pickup');
  const [quotes, setQuotes] = useState<FareQuote[] | null>(null);
  const [choice, setChoice] = useState<ServiceType>('CAR_MINI');
  const [error, setError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const [booking, setBooking] = useState(false);
  // Same request => same key, so a double-click or a retry after a network error cannot create two bookings.
  const retryKey = useRef(new RetryKey());
  const inFlight = useRef(false);

  const fail = (err: unknown) =>
    setError((err instanceof ApiError && err.code && MESSAGES[err.code]) || 'Something went wrong. Please try again.');

  const setters = { pickup: setPickup, drop: setDrop };
  const reset = () => setQuotes(null);

  /** A point chosen on the map (tap or drag): move the pin now, then fill in the street name when it arrives. */
  function pinPlaced(kind: PinKind, point: LatLng) {
    if (kind === 'driver') return; // that pin only exists on the driver map
    const lat = String(roundCoord(point.lat));
    const lng = String(roundCoord(point.lng));
    setters[kind](fieldsFrom(point, 'Pinned location'));
    reset();
    if (kind === 'pickup') setTarget('drop'); // natural flow: pickup first, then drop
    geocoder.reverse(point.lat, point.lng).then((name) => {
      // only apply if the pin has not moved again while we were waiting
      if (name) setters[kind]((f) => (f.lat === lat && f.lng === lng ? { ...f, address: name } : f));
    }).catch(() => undefined); // a missing label is fine: the pin still works
  }

  function placeChosen(kind: 'pickup' | 'drop', r: PlaceResult) {
    setters[kind](fieldsFrom(r, r.address));
    reset();
    if (kind === 'pickup') setTarget('drop');
  }

  function useMyLocation() {
    if (!navigator.geolocation) {
      setError('Your browser cannot share its location. Tap the map instead.');
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => pinPlaced('pickup', { lat: pos.coords.latitude, lng: pos.coords.longitude }),
      () => setError('We could not get your location. Allow location access, or tap the map instead.'),
      { enableHighAccuracy: true, timeout: 10_000 },
    );
  }

  async function seeFares(e: FormEvent) {
    e.preventDefault();
    setChecking(true);
    setError(null);
    try {
      setQuotes(await estimateFares(toPlace(pickup), toPlace(drop)));
    } catch (err) {
      setQuotes(null);
      fail(err);
    } finally {
      setChecking(false);
    }
  }

  async function book() {
    if (inFlight.current) return; // ignore a second click before the button has re-rendered as disabled
    inFlight.current = true;
    setBooking(true);
    setError(null);
    try {
      const pickupPlace = toPlace(pickup);
      const dropPlace = toPlace(drop);
      const key = retryKey.current.keyFor(JSON.stringify([choice, pickupPlace, dropPlace]));
      await createBooking(choice, pickupPlace, dropPlace, key);
      retryKey.current.clear(); // done: booking this same trip again later is a new booking
      navigate('/trips');
    } catch (err) {
      // keep the key: pressing Book again after an error is a retry of the SAME booking
      fail(err);
      setBooking(false);
      inFlight.current = false;
    }
  }

  const chosen = quotes?.find((q) => q.serviceType === choice);

  return (
    <div className="container">
      <div>
        <h1 className="page-title">Where to?</h1>
        <p className="page-sub">Tap the map to place your pickup and drop, or search for an address.</p>
      </div>

      <form onSubmit={seeFares} className="card form">
        <div className="segmented" role="group" aria-label="Which pin does a map tap place?">
          <button type="button" className={target === 'pickup' ? 'on' : ''} onClick={() => setTarget('pickup')}>
            <span className="pin-legend pickup" aria-hidden="true" /> Set pickup
          </button>
          <button type="button" className={target === 'drop' ? 'on' : ''} onClick={() => setTarget('drop')}>
            <span className="pin-legend drop" aria-hidden="true" /> Set drop
          </button>
        </div>

        <RouteMap
          interactive
          height={280}
          target={target}
          pickup={toLatLng(pickup)}
          drop={toLatLng(drop)}
          onPick={pinPlaced}
          label="Pick your route on the map"
        />
        <p className="hint">Drag a pin to fine-tune it.</p>

        <StopFields
          kind="pickup" label="Pickup" value={pickup} active={target === 'pickup'}
          onFocusTarget={() => setTarget('pickup')}
          onChange={(f) => { setPickup(f); reset(); }}
          onSelect={(r) => placeChosen('pickup', r)}
          onUseMyLocation={useMyLocation}
        />
        <StopFields
          kind="drop" label="Drop" value={drop} active={target === 'drop'}
          onFocusTarget={() => setTarget('drop')}
          onChange={(f) => { setDrop(f); reset(); }}
          onSelect={(r) => placeChosen('drop', r)}
        />

        <button className="btn lg block" disabled={checking}>{checking ? 'Checking fares…' : quotes ? 'Update fares' : 'See fares'}</button>
      </form>

      {error && <div role="alert" className="alert error">{error}</div>}

      {quotes && (
        <>
          <div className="section-title">
            <h2>Choose a ride</h2>
            <span className="hint">{quotes[0].distanceKm} km</span>
          </div>
          <ul className="vehicles" role="radiogroup" aria-label="Ride options">
            {quotes.map((q) => (
              <li key={q.serviceType}>
                <label className={`vehicle ${choice === q.serviceType ? 'selected' : ''}`}>
                  <input type="radio" name="service" checked={choice === q.serviceType} onChange={() => setChoice(q.serviceType)} />
                  <span className="v-icon" aria-hidden="true">{SERVICE_ICONS[q.serviceType]}</span>
                  <span className="v-body">
                    <div className="v-name">{SERVICE_LABELS[q.serviceType]}</div>
                    <div className="v-tag">{SERVICE_TAGLINES[q.serviceType]}</div>
                  </span>
                  <span>
                    <div className="v-price">{formatMoney(q.fare, q.currency)}</div>
                    <div className="v-meta">~{q.estimatedMinutes} min</div>
                  </span>
                </label>
              </li>
            ))}
          </ul>
          <div className="sticky-cta">
            <button className="btn lg block" onClick={book} disabled={booking}>
              {booking ? 'Booking…' : `Book ${SERVICE_LABELS[choice]}${chosen ? ` · ${formatMoney(chosen.fare, chosen.currency)}` : ''}`}
            </button>
          </div>
          <p className="hint" style={{ textAlign: 'center' }}>Fares are estimates and are confirmed by our servers when you book.</p>
        </>
      )}
    </div>
  );
}
