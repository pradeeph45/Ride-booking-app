import { api } from './api';

export type ServiceType = 'BIKE' | 'AUTO' | 'CAR_MINI' | 'CAR_SEDAN' | 'CAR_SUV';
export type BookingStatus = 'REQUESTED' | 'MATCHED' | 'ARRIVED' | 'STARTED' | 'COMPLETED' | 'CANCELLED' | 'EXPIRED';

export interface Place {
  lat: number;
  lng: number;
  address?: string;
}

export interface FareQuote {
  serviceType: ServiceType;
  distanceKm: number;
  estimatedMinutes: number;
  fare: number;
  currency: string;
}

export interface Booking {
  id: string;
  status: BookingStatus;
  serviceType: ServiceType;
  pickup: Place;
  drop: Place;
  distanceKm: number;
  estimatedMinutes: number;
  estimatedFare: number;
  finalFare: number | null;
  currency: string;
  driverId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface BookingPage {
  items: Booking[];
  nextCursor: string | null;
}

/** The trip steps a driver performs once a ride is theirs. Taking the ride itself is an OFFER (see offers.ts). */
export type DriverAction = 'arrive' | 'start' | 'complete';

/** Prices for every service type. The server computes them; the client never sends a price. */
export const estimateFares = (pickup: Place, drop: Place) =>
  api<FareQuote[]>('/api/bookings/estimate', { body: { pickup, drop } });

/**
 * Books a ride. Pass an idempotency key (see RetryKey) so that retrying the same request returns the booking
 * already made instead of creating a second one.
 */
export const createBooking = (serviceType: ServiceType, pickup: Place, drop: Place, idempotencyKey?: string) =>
  api<Booking>('/api/bookings', {
    body: { serviceType, pickup, drop },
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
  });

export const cancelBooking = (id: string) => api<Booking>(`/api/bookings/${id}/cancel`, { method: 'POST' });

/** My bookings, newest first. Pass the previous page's nextCursor to get the next page. */
export function listBookings(opts: { cursor?: string | null; status?: BookingStatus; limit?: number } = {}) {
  const query = new URLSearchParams();
  query.set('limit', String(opts.limit ?? 20));
  if (opts.status) query.set('status', opts.status);
  if (opts.cursor) query.set('cursor', opts.cursor);
  return api<BookingPage>(`/api/bookings?${query.toString()}`);
}

export const driverAction = (id: string, action: DriverAction) =>
  api<Booking>(`/api/bookings/${id}/${action}`, { method: 'POST' });

export const SERVICE_LABELS: Record<ServiceType, string> = {
  BIKE: 'Bike',
  AUTO: 'Auto',
  CAR_MINI: 'Car · Mini',
  CAR_SEDAN: 'Car · Sedan',
  CAR_SUV: 'Car · SUV',
};

export const SERVICE_ICONS: Record<ServiceType, string> = {
  BIKE: '🏍️',
  AUTO: '🛺',
  CAR_MINI: '🚗',
  CAR_SEDAN: '🚘',
  CAR_SUV: '🚙',
};

export const SERVICE_TAGLINES: Record<ServiceType, string> = {
  BIKE: 'Fastest through traffic',
  AUTO: 'Affordable city rides',
  CAR_MINI: 'Comfortable hatchbacks',
  CAR_SEDAN: 'Extra legroom',
  CAR_SUV: 'Room for the whole group',
};

export function formatMoney(amount: number, currency: string): string {
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency }).format(amount);
}
