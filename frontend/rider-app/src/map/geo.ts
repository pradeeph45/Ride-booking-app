export interface LatLng {
  lat: number;
  lng: number;
}

/** Default map centre: Bengaluru. */
export const DEFAULT_CENTER: LatLng = { lat: 12.9716, lng: 77.5946 };

const EARTH_RADIUS_KM = 6371.0088;

/** Straight-line distance between two points, in kilometres. */
export function haversineKm(a: LatLng, b: LatLng): number {
  const rad = (d: number) => (d * Math.PI) / 180;
  const dLat = rad(b.lat - a.lat);
  const dLng = rad(b.lng - a.lng);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(h));
}

/** Real coordinates only: finite numbers inside the Earth's range (matches the server's rules). */
export function isValidLatLng(lat: number, lng: number): boolean {
  return Number.isFinite(lat) && Number.isFinite(lng) && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
}

/** Six decimals is about 10 cm, far more precise than any phone GPS. */
export function roundCoord(n: number): number {
  return Math.round(n * 1e6) / 1e6;
}

const MAX_ADDRESS = 80;

/**
 * "MG Road, Shanthala Nagar, Bengaluru, Bangalore North, Karnataka, 560001, India"
 * becomes "MG Road, Shanthala Nagar, Bengaluru": the first few meaningful parts, no postcodes, bounded length.
 */
export function shortenAddress(displayName: string): string {
  const parts = displayName
    .split(',')
    .map((p) => p.trim())
    .filter((p) => p && !/^\d+$/.test(p));
  const short = parts.slice(0, 3).join(', ');
  return short.length > MAX_ADDRESS ? `${short.slice(0, MAX_ADDRESS - 1)}…` : short;
}
