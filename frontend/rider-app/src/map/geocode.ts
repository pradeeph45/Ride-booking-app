import { roundCoord, shortenAddress, type LatLng } from './geo';

export interface PlaceResult extends LatLng {
  address: string;
}

export class GeocodeError extends Error {
  constructor(message = 'Address search is unavailable right now') {
    super(message);
  }
}

interface Options {
  fetchFn?: typeof fetch;
  baseUrl?: string;
  minIntervalMs?: number;
  now?: () => number;
  sleep?: (ms: number) => Promise<void>;
}

const MIN_QUERY_LENGTH = 3;
const MAX_CACHE = 100;

/**
 * Address search and "what is at this point?" using OpenStreetMap's public Nominatim service.
 *
 * Nominatim's usage policy allows at most ONE request per second and forbids as-you-type autocomplete,
 * so requests are queued with a minimum gap, answers are cached, and searches only run when the user asks.
 * Only the typed address or the tapped coordinates are sent, nothing about the signed-in user.
 * Before production traffic, point {@code baseUrl} at your own geocoder (self-hosted Nominatim/Photon or a
 * paid provider); the public server is meant for light use.
 */
export class Geocoder {
  private readonly fetchFn: typeof fetch;
  private readonly baseUrl: string;
  private readonly minIntervalMs: number;
  private readonly now: () => number;
  private readonly sleep: (ms: number) => Promise<void>;
  private readonly cache = new Map<string, unknown>();
  private queue: Promise<unknown> = Promise.resolve();
  private lastRequestAt = Number.NEGATIVE_INFINITY;

  constructor(opts: Options = {}) {
    this.fetchFn = opts.fetchFn ?? ((...args) => fetch(...args)); // resolved at call time so tests and mocks can replace fetch
    this.baseUrl = opts.baseUrl ?? 'https://nominatim.openstreetmap.org';
    this.minIntervalMs = opts.minIntervalMs ?? 1100;
    this.now = opts.now ?? (() => Date.now());
    this.sleep = opts.sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
  }

  async search(query: string): Promise<PlaceResult[]> {
    const q = query.trim();
    if (q.length < MIN_QUERY_LENGTH) return [];
    const params = new URLSearchParams({ format: 'jsonv2', limit: '5', addressdetails: '0', q });
    const rows = await this.cached<unknown[]>(`s:${q.toLowerCase()}`, `/search?${params}`);
    return rows.flatMap((row) => {
      const r = row as { lat?: string; lon?: string; display_name?: string };
      const lat = Number(r.lat);
      const lng = Number(r.lon);
      return Number.isFinite(lat) && Number.isFinite(lng) && r.display_name
        ? [{ lat, lng, address: shortenAddress(r.display_name) }]
        : [];
    });
  }

  /** A short label for a point, or null when the map has nothing there. */
  async reverse(lat: number, lng: number): Promise<string | null> {
    const params = new URLSearchParams({ format: 'jsonv2', zoom: '18', lat: String(roundCoord(lat)), lon: String(roundCoord(lng)) });
    // about 10 m resolution for the cache key: the street label rarely changes within 10 m, so small pin nudges cost no request
    const key = `r:${lat.toFixed(4)},${lng.toFixed(4)}`;
    const body = await this.cached<{ display_name?: string; error?: string }>(key, `/reverse?${params}`);
    return body.display_name && !body.error ? shortenAddress(body.display_name) : null;
  }

  private async cached<T>(key: string, path: string): Promise<T> {
    if (this.cache.has(key)) return this.cache.get(key) as T;
    const result = await this.schedule<T>(path);
    if (this.cache.size >= MAX_CACHE) this.cache.delete(this.cache.keys().next().value as string);
    this.cache.set(key, result); // failures throw before this line, so they are never cached
    return result;
  }

  /** One request at a time, never closer together than minIntervalMs. */
  private schedule<T>(path: string): Promise<T> {
    const run = async (): Promise<T> => {
      const wait = this.lastRequestAt + this.minIntervalMs - this.now();
      if (wait > 0) await this.sleep(wait);
      this.lastRequestAt = this.now();
      let response: Response;
      try {
        response = await this.fetchFn(`${this.baseUrl}${path}`, { headers: { Accept: 'application/json' } });
      } catch {
        throw new GeocodeError();
      }
      if (!response.ok) throw new GeocodeError();
      return (await response.json()) as T;
    };
    const next = this.queue.then(run, run);
    this.queue = next.catch(() => undefined); // a failure must not block the queue
    return next;
  }
}

/** Shared by the whole app so the one-request-per-second limit applies across every component. */
export const geocoder = new Geocoder();
