import { useEffect, useState } from 'react';
import { fetchTrackingSnapshot, watchDriver, type DriverPosition } from '../auth/tracking';

/**
 * The driver's live position for one trip. It shows the last known position immediately (a plain request), then
 * keeps it moving from the WebSocket. Pass {@code enabled=false} when there is nothing to follow.
 */
export function useLiveDriver(bookingId: string, enabled: boolean) {
  const [position, setPosition] = useState<DriverPosition | null>(null);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    fetchTrackingSnapshot(bookingId)
      .then((snapshot) => {
        // a live message that already arrived is newer than the snapshot, so only fill an empty slot
        if (!cancelled && snapshot?.location) setPosition((current) => current ?? snapshot.location);
      })
      .catch(() => undefined); // the live feed will fill the position in anyway
    const stop = watchDriver(bookingId, { onPosition: setPosition, onConnection: setConnected });
    return () => {
      cancelled = true;
      stop();
      setConnected(false);
    };
  }, [bookingId, enabled]);

  return { position, connected };
}
