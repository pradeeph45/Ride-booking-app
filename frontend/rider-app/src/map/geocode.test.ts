import { beforeEach, describe, expect, it, vi } from 'vitest';
import { GeocodeError, Geocoder } from './geocode';

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('Geocoder', () => {
  const fetchFn = vi.fn();
  let clock = 0;
  const waits: number[] = [];
  let geocoder: Geocoder;

  beforeEach(() => {
    fetchFn.mockReset();
    clock = 10_000;
    waits.length = 0;
    geocoder = new Geocoder({
      fetchFn: fetchFn as unknown as typeof fetch,
      baseUrl: 'https://geo.test',
      minIntervalMs: 1000,
      now: () => clock,
      sleep: async (ms) => { waits.push(ms); clock += ms; },
    });
  });

  it('searches and maps results to coordinates plus a short address', async () => {
    fetchFn.mockResolvedValueOnce(json([
      { lat: '12.9756', lon: '77.6050', display_name: 'MG Road, Shanthala Nagar, Bengaluru, Karnataka, 560001, India' },
    ]));

    const results = await geocoder.search('MG Road');

    expect(results).toEqual([{ lat: 12.9756, lng: 77.605, address: 'MG Road, Shanthala Nagar, Bengaluru' }]);
    const url = new URL(fetchFn.mock.calls[0][0]);
    expect(url.origin + url.pathname).toBe('https://geo.test/search');
    expect(url.searchParams.get('q')).toBe('MG Road');
    expect(url.searchParams.get('format')).toBe('jsonv2');
    expect(url.searchParams.get('limit')).toBe('5');
  });

  it('does not call the service for very short or blank queries', async () => {
    expect(await geocoder.search('  ')).toEqual([]);
    expect(await geocoder.search('ab')).toEqual([]);
    expect(fetchFn).not.toHaveBeenCalled();
  });

  it('ignores malformed rows instead of crashing the page', async () => {
    fetchFn.mockResolvedValueOnce(json([
      { lat: 'not-a-number', lon: '77', display_name: 'Bad' },
      { lat: '12.9', lon: '77.6', display_name: 'Good Place, Bengaluru' },
      { display_name: 'No coordinates' },
    ]));

    expect(await geocoder.search('place')).toEqual([{ lat: 12.9, lng: 77.6, address: 'Good Place, Bengaluru' }]);
  });

  it('reverse-geocodes a point into a short label, or null when nothing is known there', async () => {
    fetchFn.mockResolvedValueOnce(json({ display_name: 'Church Street, Shanthala Nagar, Bengaluru, India' }));
    expect(await geocoder.reverse(12.9756, 77.605)).toBe('Church Street, Shanthala Nagar, Bengaluru');

    fetchFn.mockResolvedValueOnce(json({ error: 'Unable to geocode' }));
    expect(await geocoder.reverse(0, 0)).toBeNull();
  });

  it('respects the one-request-per-second usage policy by spacing calls out', async () => {
    fetchFn.mockImplementation(async () => json([]));

    await geocoder.search('first query');
    await geocoder.search('second query');
    await geocoder.search('third query');

    expect(fetchFn).toHaveBeenCalledTimes(3);
    expect(waits.filter((w) => w > 0)).toEqual([1000, 1000]); // the first call goes out immediately
  });

  it('remembers answers, so repeating a search costs no request', async () => {
    fetchFn.mockImplementation(async () => json([{ lat: '12.9', lon: '77.6', display_name: 'Somewhere, Bengaluru' }]));

    await geocoder.search('Somewhere');
    await geocoder.search('  somewhere ');           // same query, different spacing and case
    await geocoder.reverse(12.97561, 77.60501);
    await geocoder.reverse(12.97562, 77.60502);      // within ~2 m: same cache entry

    expect(fetchFn).toHaveBeenCalledTimes(2);
  });

  it('reports a failed or unavailable service with a clear error', async () => {
    fetchFn.mockResolvedValueOnce(json({}, 503));
    await expect(geocoder.search('anywhere')).rejects.toBeInstanceOf(GeocodeError);

    fetchFn.mockRejectedValueOnce(new TypeError('network down'));
    await expect(geocoder.search('elsewhere')).rejects.toBeInstanceOf(GeocodeError);
  });

  it('does not cache failures, so a retry can succeed', async () => {
    fetchFn.mockResolvedValueOnce(json({}, 500)).mockResolvedValueOnce(json([{ lat: '1', lon: '2', display_name: 'A, B' }]));

    await expect(geocoder.search('flaky place')).rejects.toBeInstanceOf(GeocodeError);

    expect(await geocoder.search('flaky place')).toHaveLength(1);
  });
});
