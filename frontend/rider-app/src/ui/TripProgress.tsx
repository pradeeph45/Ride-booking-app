import type { BookingStatus } from '../auth/bookings';

const STEPS: { status: BookingStatus; label: string }[] = [
  { status: 'REQUESTED', label: 'Requested' },
  { status: 'MATCHED', label: 'Driver found' },
  { status: 'ARRIVED', label: 'Arrived' },
  { status: 'STARTED', label: 'On trip' },
  { status: 'COMPLETED', label: 'Done' },
];

/** The five stages of a ride, with the current one highlighted. Cancelled trips show nothing here. */
export default function TripProgress({ status }: { status: BookingStatus }) {
  const current = STEPS.findIndex((s) => s.status === status);
  if (current < 0) return null;
  return (
    <ol className="progress" aria-label={`Trip progress: ${STEPS[current].label}`} style={{ listStyle: 'none', margin: 0, padding: 0 }}>
      {STEPS.map((step, i) => (
        <li key={step.status} className={`step ${i <= current ? 'done' : ''} ${i === current ? 'now' : ''}`} aria-current={i === current ? 'step' : undefined}>
          <span className="bullet">{i < current || status === 'COMPLETED' ? '✓' : ''}</span>
          {step.label}
        </li>
      ))}
    </ol>
  );
}
