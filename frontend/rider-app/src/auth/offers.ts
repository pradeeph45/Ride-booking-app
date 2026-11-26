import { api } from './api';
import type { Booking, Place, ServiceType } from './bookings';

/** A ride offered to this driver. They have until {@code expiresAt} to answer. The rider is never identified. */
export interface Offer {
  id: string;
  bookingId: string;
  expiresAt: string;
  pickupDistanceKm: number;
  serviceType: ServiceType;
  pickup: Place;
  drop: Place;
  distanceKm: number;
  estimatedMinutes: number;
  estimatedFare: number;
  currency: string;
}

export const myOffers = () => api<Offer[]>('/api/offers/mine');

/** Taking the ride. Repeating the call is safe. Rejects with 409 OFFER_EXPIRED if the time ran out. */
export const acceptOffer = (id: string) => api<Booking>(`/api/offers/${id}/accept`, { method: 'POST' });

export const rejectOffer = (id: string) => api<void>(`/api/offers/${id}/reject`, { method: 'POST' });
