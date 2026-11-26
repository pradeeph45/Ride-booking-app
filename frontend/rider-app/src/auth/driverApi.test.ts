import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearSession, login } from './api';
import { getProfile, goOffline, goOnline, saveProfile, sendLocation } from './drivers';
import { acceptOffer, myOffers, rejectOffer } from './offers';
import { fetchTrackingSnapshot, parseLocation } from './tracking';

function json(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('driver, offer and tracking clients', () => {
  const fetchMock = vi.fn();

  beforeEach(async () => {
    clearSession();
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValueOnce(json(200, { accessToken: 'AT', expiresIn: 899 }));
    await login('d@x.com', '123456');
    fetchMock.mockReset();
  });
  afterEach(() => vi.unstubAllGlobals());

  // ---- profile -------------------------------------------------------------------------------

  it('treats "no vehicle registered yet" as null, not as an error', async () => {
    fetchMock.mockResolvedValueOnce(json(404, { code: 'DRIVER_PROFILE_REQUIRED' }));

    expect(await getProfile()).toBeNull();
  });

  it('still reports real failures', async () => {
    fetchMock.mockResolvedValueOnce(json(500, { code: 'INTERNAL_ERROR' }));

    await expect(getProfile()).rejects.toMatchObject({ status: 500 });
  });

  it('registers or updates the vehicle with PUT and the bearer token', async () => {
    fetchMock.mockResolvedValueOnce(json(201, { vehicleType: 'AUTO', plateNumber: 'KA01AB1234', vehicleModel: 'Bajaj', status: 'OFFLINE' }));

    await saveProfile({ vehicleType: 'AUTO', plateNumber: 'KA01AB1234', vehicleModel: 'Bajaj' });

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/drivers/me');
    expect(init.method).toBe('PUT');
    expect(init.headers.Authorization).toBe('Bearer AT');
    expect(JSON.parse(init.body)).toEqual({ vehicleType: 'AUTO', plateNumber: 'KA01AB1234', vehicleModel: 'Bajaj' });
  });

  it('goes online and offline through their own endpoints', async () => {
    fetchMock.mockImplementation(async () => json(200, { status: 'ONLINE' }));

    await goOnline();
    await goOffline();

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(['/api/drivers/me/online', '/api/drivers/me/offline']);
  });

  // ---- location pings ------------------------------------------------------------------------

  it('sends a ping and reports it was accepted', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));

    expect(await sendLocation({ lat: 12.97, lng: 77.6 }, 90)).toBe('ok');

    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ lat: 12.97, lng: 77.6, heading: 90 });
  });

  it('reports a throttled ping (429) and an offline driver (409) as outcomes the caller can act on', async () => {
    fetchMock.mockResolvedValueOnce(json(429, { code: 'TOO_MANY_UPDATES' }));
    expect(await sendLocation({ lat: 12.97, lng: 77.6 })).toBe('throttled');

    fetchMock.mockResolvedValueOnce(json(409, { code: 'DRIVER_OFFLINE' }));
    expect(await sendLocation({ lat: 12.97, lng: 77.6 })).toBe('offline');
  });

  it('lets unexpected ping failures surface', async () => {
    fetchMock.mockResolvedValueOnce(json(400, { code: 'INVALID_REQUEST' }));

    await expect(sendLocation({ lat: 99, lng: 77.6 })).rejects.toMatchObject({ status: 400 });
  });

  // ---- offers --------------------------------------------------------------------------------

  it('lists my live offers and answers them', async () => {
    fetchMock.mockResolvedValueOnce(json(200, [{ id: 'o1' }]));
    expect(await myOffers()).toEqual([{ id: 'o1' }]);

    fetchMock.mockResolvedValueOnce(json(200, { id: 'b1', status: 'MATCHED' }));
    await acceptOffer('o1');
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await rejectOffer('o1');

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(['/api/offers/mine', '/api/offers/o1/accept', '/api/offers/o1/reject']);
  });

  it('surfaces an expired offer as a 409 with the server code', async () => {
    fetchMock.mockResolvedValueOnce(json(409, { code: 'OFFER_EXPIRED' }));

    await expect(acceptOffer('o1')).rejects.toMatchObject({ status: 409, code: 'OFFER_EXPIRED' });
  });

  // ---- tracking ------------------------------------------------------------------------------

  it('fetches the last known position of a trip, or null when the trip is not trackable', async () => {
    fetchMock.mockResolvedValueOnce(json(200, { status: 'MATCHED', location: { lat: 12.97, lng: 77.6, heading: 10, ts: 1 } }));
    expect(await fetchTrackingSnapshot('b1')).toEqual({ status: 'MATCHED', location: { lat: 12.97, lng: 77.6, heading: 10, ts: 1 } });

    fetchMock.mockResolvedValueOnce(json(404, { code: 'NOT_FOUND' }));
    expect(await fetchTrackingSnapshot('b2')).toBeNull();
  });

  it('parses a live position message and ignores anything malformed', () => {
    expect(parseLocation('{"lat":12.976,"lng":77.6055,"heading":45.0,"ts":5}')).toEqual({ lat: 12.976, lng: 77.6055, heading: 45, ts: 5 });
    expect(parseLocation('{"lat":12.976,"lng":77.6055,"heading":null,"ts":5}')?.heading).toBeNull();

    for (const junk of ['not json', '{}', '{"lat":"x","lng":1}', '{"lat":95,"lng":1}', '{"lat":1,"lng":190}', '']) {
      expect(parseLocation(junk)).toBeNull();
    }
  });
});
