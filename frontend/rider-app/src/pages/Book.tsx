import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '../auth/api';
import {
  SERVICE_ICONS, SERVICE_LABELS, SERVICE_TAGLINES, createBooking, estimateFares, formatMoney,
  type FareQuote, type Place, type ServiceType,
} from '../auth/bookings';

// Bengaluru sample points. A map picker replaces these inputs in the map phase.
const DEFAULT_PICKUP = { lat: '12.9756', lng: '77.6050', address: 'MG Road' };
const DEFAULT_DROP = { lat: '12.9352', lng: '77.6245', address: 'Koramangala' };

type Fields = { lat: string; lng: string; address: string };

const toPlace = (f: Fields): Place => ({ lat: Number(f.lat), lng: Number(f.lng), address: f.address || undefined });

const MESSAGES: Record<string, string> = {
  INVALID_REQUEST: 'Pickup and drop are too close together, or the location is invalid.',
  VALIDATION_FAILED: 'Please check the coordinates.',
};

function StopFields({ label, value, onChange }: { label: string; value: Fields; onChange: (f: Fields) => void }) {
  const set = (key: keyof Fields) => (e: React.ChangeEvent<HTMLInputElement>) => onChange({ ...value, [key]: e.target.value });
  return (
    <div>
      <label className="field">
        {label}
        <input value={value.address} onChange={set('address')} maxLength={255} placeholder="Search address" required />
      </label>
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
  const [quotes, setQuotes] = useState<FareQuote[] | null>(null);
  const [choice, setChoice] = useState<ServiceType>('CAR_MINI');
  const [error, setError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const [booking, setBooking] = useState(false);

  const fail = (err: unknown) =>
    setError((err instanceof ApiError && err.code && MESSAGES[err.code]) || 'Something went wrong. Please try again.');

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
    setBooking(true);
    setError(null);
    try {
      await createBooking(choice, toPlace(pickup), toPlace(drop));
      navigate('/trips');
    } catch (err) {
      fail(err);
      setBooking(false);
    }
  }

  const chosen = quotes?.find((q) => q.serviceType === choice);
  const reset = () => setQuotes(null);

  return (
    <div className="container">
      <div>
        <h1 className="page-title">Where to?</h1>
        <p className="page-sub">Choose your route, compare fares, then book.</p>
      </div>

      <form onSubmit={seeFares} className="card form">
        <div className="route">
          <div className="stop">
            <div className="rail" aria-hidden="true"><span className="dot" /><span className="line" /></div>
            <StopFields label="Pickup" value={pickup} onChange={(f) => { setPickup(f); reset(); }} />
          </div>
          <div className="stop">
            <div className="rail" aria-hidden="true"><span className="dot drop" /></div>
            <StopFields label="Drop" value={drop} onChange={(f) => { setDrop(f); reset(); }} />
          </div>
        </div>
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
