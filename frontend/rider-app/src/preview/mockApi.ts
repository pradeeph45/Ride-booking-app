// Canned responses so every screen can be previewed without any backend running.

const now = Date.now();
const ago = (ms: number) => new Date(now - ms).toISOString();
const MIN = 60_000;
const HOUR = 3_600_000;
const DAY = 86_400_000;

const place = (address: string, lat: number, lng: number) => ({ lat, lng, address });
const mg = place('MG Road, Bengaluru', 12.9756, 77.605);
const kor = place('Koramangala 5th Block', 12.9352, 77.6245);
const air = place('Kempegowda Airport', 13.1986, 77.7066);

function trip(over: Record<string, unknown>) {
  return {
    id: crypto.randomUUID(), status: 'COMPLETED', serviceType: 'CAR_MINI', pickup: mg, drop: kor,
    distanceKm: 6.45, estimatedMinutes: 16, estimatedFare: 152.9, finalFare: null, currency: 'INR',
    driverId: null, createdAt: ago(2 * HOUR), updatedAt: ago(HOUR), ...over,
  };
}

const TRIPS = [
  trip({ status: 'MATCHED', serviceType: 'CAR_SEDAN', estimatedFare: 180.05, createdAt: ago(3 * MIN), driverId: 'd1' }),
  trip({ status: 'REQUESTED', serviceType: 'BIKE', estimatedFare: 65.7, createdAt: ago(40_000) }),
  trip({ status: 'COMPLETED', serviceType: 'AUTO', estimatedFare: 118.95, finalFare: 118.95, createdAt: ago(DAY), driverId: 'd1' }),
  trip({ status: 'STARTED', serviceType: 'CAR_SUV', pickup: mg, drop: air, distanceKm: 38.2, estimatedMinutes: 72, estimatedFare: 920.5, createdAt: ago(20 * MIN), driverId: 'd1' }),
  trip({ status: 'CANCELLED', serviceType: 'CAR_MINI', createdAt: ago(3 * DAY) }),
];

const CHROME_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/158.0.0.0 Sa';

const SESSIONS = [
  { id: 's1', deviceName: CHROME_UA, ipAddress: '127.0.0.1', createdAt: ago(DAY), lastSeenAt: ago(5_000), current: true },
  { id: 's2', deviceName: 'curl/8.7.1', ipAddress: '127.0.0.1', createdAt: ago(DAY), lastSeenAt: ago(5 * HOUR), current: false },
  { id: 's3', deviceName: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile Safari/604.1', ipAddress: '49.37.12.8', createdAt: ago(9 * DAY), lastSeenAt: ago(2 * DAY), current: false },
];

const QUOTES = [
  ['BIKE', 65.7, 14], ['AUTO', 118.95, 18], ['CAR_MINI', 152.9, 16], ['CAR_SEDAN', 180.05, 16], ['CAR_SUV', 230.1, 16],
].map(([serviceType, fare, estimatedMinutes]) => ({ serviceType, fare, estimatedMinutes, distanceKm: 6.45, currency: 'INR' }));

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

export function installMockApi(opts: { role: 'RIDER' | 'DRIVER'; empty: boolean; anon?: boolean }) {
  const profile = {
    id: 'u1', role: opts.role, createdAt: ago(40 * DAY),
    email: opts.role === 'DRIVER' ? 'asha.driver@example.com' : 'demo.rider@example.com',
  };
  window.fetch = async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('nominatim.openstreetmap.org/search')) return json([
      { lat: '12.9719', lon: '77.6412', display_name: 'Indiranagar, Bengaluru, Karnataka, 560038, India' },
      { lat: '12.9784', lon: '77.6408', display_name: '100 Feet Road, Indiranagar, Bengaluru, Karnataka, India' },
    ]);
    if (url.includes('nominatim.openstreetmap.org/reverse')) return json({ display_name: 'Church Street, Shanthala Nagar, Bengaluru, Karnataka, 560001, India' });
    if (url.includes('/bff/refresh')) return opts.anon ? json({}, 401) : json({ accessToken: 'preview', expiresIn: 899 });
    if (url.includes('/api/auth/otp/request')) return new Response(null, { status: 202 });
    if (url.includes('/api/users/me/sessions')) return json(SESSIONS);
    if (url.includes('/api/users/me')) return json(profile);
    if (url.includes('/api/bookings/estimate')) return json(QUOTES);
    if (url.includes('/api/bookings/open')) return json(opts.empty ? [] : TRIPS.filter((t) => t.status === 'REQUESTED').concat(trip({ status: 'REQUESTED', serviceType: 'AUTO', estimatedFare: 118.95, createdAt: ago(2 * MIN) })));
    if (url.includes('/api/bookings')) {
      const status = new URL(url, window.location.origin).searchParams.get('status');
      const items = opts.empty ? [] : TRIPS.filter((t) => !status || t.status === status);
      return json({ items: opts.role === 'DRIVER' ? items.filter((t) => t.status === 'STARTED') : items, nextCursor: null });
    }
    return json({}, 404);
  };
}
