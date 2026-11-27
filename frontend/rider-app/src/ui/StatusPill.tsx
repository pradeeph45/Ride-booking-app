import type { BookingStatus } from '../auth/bookings';

const LABELS: Record<BookingStatus, string> = {
  REQUESTED: 'Finding driver',
  MATCHED: 'Driver on the way',
  ARRIVED: 'Driver arrived',
  STARTED: 'On trip',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
  EXPIRED: 'No driver found',
};

export default function StatusPill({ status }: { status: BookingStatus }) {
  return <span className={`pill status-${status.toLowerCase()}`}>{LABELS[status]}</span>;
}
