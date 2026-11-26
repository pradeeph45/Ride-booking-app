import { Client } from '@stomp/stompjs';
import { ApiError, api, getAccessToken, refreshSession } from './api';
import { isValidLatLng } from '../map/geo';

/** Where a driver is, and which way they face. Never says who they are. */
export interface DriverPosition {
  lat: number;
  lng: number;
  heading: number | null;
  ts: number;
}

export interface TrackingSnapshot {
  status: string;
  location: DriverPosition | null;
}

/** Reads one position message from the server. Anything that is not a real position becomes null, never a crash. */
export function parseLocation(text: string): DriverPosition | null {
  try {
    const m = JSON.parse(text) as Record<string, unknown>;
    const { lat, lng, heading, ts } = m;
    if (typeof lat !== 'number' || typeof lng !== 'number' || !isValidLatLng(lat, lng)) return null;
    return { lat, lng, heading: typeof heading === 'number' ? heading : null, ts: typeof ts === 'number' ? ts : 0 };
  } catch {
    return null;
  }
}

/** The last known position, for the first paint and after a dropped connection. Null if the trip is not trackable. */
export async function fetchTrackingSnapshot(bookingId: string): Promise<TrackingSnapshot | null> {
  try {
    return await api<TrackingSnapshot>(`/api/tracking/bookings/${bookingId}`);
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) return null;
    throw err;
  }
}

interface Handlers {
  onPosition: (position: DriverPosition) => void;
  onConnection?: (connected: boolean) => void;
}

/**
 * Watches one trip's driver live over a WebSocket. The access token goes in the STOMP CONNECT frame (a browser
 * WebSocket cannot set headers, and a token in the URL would end up in logs). Reconnects by itself, and takes a
 * fresh token each time because access tokens last only 15 minutes. Returns a function that stops watching.
 */
export function watchDriver(bookingId: string, handlers: Handlers): () => void {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
  const client = new Client({
    brokerURL: `${scheme}://${window.location.host}/ws`,
    reconnectDelay: 3000,
    heartbeatIncoming: 10_000,
    heartbeatOutgoing: 10_000,
    beforeConnect: async () => {
      if (!getAccessToken()) await refreshSession();
      client.connectHeaders = { Authorization: `Bearer ${getAccessToken() ?? ''}` };
    },
    onConnect: () => {
      handlers.onConnection?.(true);
      client.subscribe(`/topic/bookings/${bookingId}/location`, (message) => {
        const position = parseLocation(message.body);
        if (position) handlers.onPosition(position);
      });
    },
    onWebSocketClose: () => handlers.onConnection?.(false),
  });
  client.activate();
  return () => {
    void client.deactivate();
  };
}
