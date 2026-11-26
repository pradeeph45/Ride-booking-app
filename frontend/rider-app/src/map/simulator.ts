import { haversineKm, type LatLng } from './geo';

/**
 * Moves a point a fixed distance toward a target, for the driver simulator. A straight line is plenty for a demo: the
 * trip is only a few kilometres, so the curvature of the Earth is far below a metre.
 */
export function stepToward(from: LatLng, to: LatLng, meters: number): { point: LatLng; arrived: boolean } {
  const distanceM = haversineKm(from, to) * 1000;
  if (distanceM <= meters) {
    return { point: { lat: to.lat, lng: to.lng }, arrived: true }; // snap to the target instead of overshooting
  }
  const fraction = meters / distanceM;
  return {
    point: { lat: from.lat + (to.lat - from.lat) * fraction, lng: from.lng + (to.lng - from.lng) * fraction },
    arrived: false,
  };
}

/** Compass direction from one point to another in degrees: 0 north, 90 east, 180 south, 270 west. */
export function bearing(from: LatLng, to: LatLng): number {
  const rad = (d: number) => (d * Math.PI) / 180;
  const dLng = rad(to.lng - from.lng);
  const y = Math.sin(dLng) * Math.cos(rad(to.lat));
  const x = Math.cos(rad(from.lat)) * Math.sin(rad(to.lat)) - Math.sin(rad(from.lat)) * Math.cos(rad(to.lat)) * Math.cos(dLng);
  return (((Math.atan2(y, x) * 180) / Math.PI) + 360) % 360;
}
