import { useEffect, useRef, useState } from 'react';
import { sendLocation } from '../auth/drivers';
import type { LatLng } from '../map/geo';
import { bearing, stepToward } from '../map/simulator';

export type LocationSource = 'simulator' | 'gps';

/** How far the simulated car moves per ping: about 130 m, so a 5 km trip takes roughly two minutes to demo. */
export const SIMULATOR_STEP_METERS = 130;
export const PING_INTERVAL_MS = 3000;

interface Options {
  /** Share only while online. */
  enabled: boolean;
  source: LocationSource;
  /** Where the simulated car starts. */
  start: LatLng;
  /** Where the simulated car is heading now (pickup, drop), or null to stay put. */
  destination: LatLng | null;
  /** The server stopped considering this driver online. */
  onServerOffline: () => void;
}

/**
 * Sends the driver's position to the server every few seconds while online, either from the phone's GPS or from a
 * simulated car that drives toward {@code destination}. Returns where the driver is, for drawing on the map.
 */
export function useLocationSharing({ enabled, source, start, destination, onServerOffline }: Options) {
  const [position, setPosition] = useState<LatLng>(start);
  const [error, setError] = useState<string | null>(null);
  const [lastPingAt, setLastPingAt] = useState<number | null>(null);
  const gps = useRef<LatLng | null>(null);
  const current = useRef<LatLng>(start);
  const destinationRef = useRef(destination);
  const offlineRef = useRef(onServerOffline);

  destinationRef.current = destination;
  offlineRef.current = onServerOffline;

  // moving the starting point (tap on the map) teleports the simulated car there
  useEffect(() => {
    current.current = start;
    setPosition(start);
  }, [start.lat, start.lng]); // eslint-disable-line react-hooks/exhaustive-deps

  // GPS: remember the latest fix; the ping loop below reads it
  useEffect(() => {
    if (!enabled || source !== 'gps') return;
    if (!navigator.geolocation) {
      setError('This device cannot share its location. Switch to the simulator.');
      return;
    }
    const watch = navigator.geolocation.watchPosition(
      (p) => {
        gps.current = { lat: p.coords.latitude, lng: p.coords.longitude };
        current.current = gps.current;
        setPosition(gps.current);
        setError(null);
      },
      () => setError('Could not read your location. Allow location access, or use the simulator.'),
      { enableHighAccuracy: true, maximumAge: 2000 },
    );
    return () => navigator.geolocation.clearWatch(watch);
  }, [enabled, source]);

  useEffect(() => {
    if (!enabled) return;
    let busy = false;
    const tick = async () => {
      if (busy) return; // never let slow pings pile up
      busy = true;
      try {
        let heading: number | undefined;
        if (source === 'simulator') {
          const target = destinationRef.current;
          if (target) {
            const next = stepToward(current.current, target, SIMULATOR_STEP_METERS);
            heading = bearing(current.current, target);
            current.current = next.point;
            setPosition(next.point);
          }
        } else if (!gps.current) {
          return; // no GPS fix yet
        }
        const result = await sendLocation(current.current, heading);
        if (result === 'ok') {
          setLastPingAt(Date.now());
          setError(null);
        } else if (result === 'offline') {
          offlineRef.current();
        } // 'throttled': we were a little early; the next tick is fine
      } catch {
        setError('Could not reach the server. Retrying…');
      } finally {
        busy = false;
      }
    };
    void tick();
    const timer = setInterval(() => void tick(), PING_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [enabled, source]);

  return { position, error, lastPingAt };
}
