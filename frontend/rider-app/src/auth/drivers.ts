import { ApiError, api } from './api';
import type { ServiceType } from './bookings';
import type { LatLng } from '../map/geo';

export interface DriverProfile {
  vehicleType: ServiceType;
  plateNumber: string;
  vehicleModel: string;
  status: 'ONLINE' | 'OFFLINE';
}

export type VehicleForm = Pick<DriverProfile, 'vehicleType' | 'plateNumber' | 'vehicleModel'>;

/** The driver's vehicle, or null if they have not registered one yet (a normal first-time state, not an error). */
export async function getProfile(): Promise<DriverProfile | null> {
  try {
    return await api<DriverProfile>('/api/drivers/me');
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) return null;
    throw err;
  }
}

export const saveProfile = (vehicle: VehicleForm) =>
  api<DriverProfile>('/api/drivers/me', { method: 'PUT', body: vehicle });

export const goOnline = () => api<DriverProfile>('/api/drivers/me/online', { method: 'POST' });
export const goOffline = () => api<DriverProfile>('/api/drivers/me/offline', { method: 'POST' });

export type PingResult = 'ok' | 'throttled' | 'offline';

/**
 * One GPS ping. The server allows one per second, so a 429 just means "too soon": skip this one. A 409 means the
 * server no longer considers this driver online, so the caller should stop sharing.
 */
export async function sendLocation(point: LatLng, heading?: number): Promise<PingResult> {
  try {
    await api<void>('/api/drivers/me/location', { method: 'POST', body: { lat: point.lat, lng: point.lng, heading } });
    return 'ok';
  } catch (err) {
    if (err instanceof ApiError && err.status === 429) return 'throttled';
    if (err instanceof ApiError && err.status === 409 && err.code === 'DRIVER_OFFLINE') return 'offline';
    throw err;
  }
}
