import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearSession, login } from './api';
import { getMyRating, getMyRatingSummary, submitRating } from './ratings';

function json(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('rating client', () => {
  const fetchMock = vi.fn();

  beforeEach(async () => {
    clearSession();
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockResolvedValueOnce(json(200, { accessToken: 'AT', expiresIn: 899 }));
    await login('r@x.com', '123456');
    fetchMock.mockReset();
  });
  afterEach(() => vi.unstubAllGlobals());

  it('treats "not rated yet" (204) as null', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));

    expect(await getMyRating('t1')).toBeNull();
    expect(fetchMock.mock.calls[0][0]).toBe('/api/bookings/t1/rating');
  });

  it('returns the rating I gave', async () => {
    fetchMock.mockResolvedValueOnce(json(200, { bookingId: 't1', score: 4, comment: 'Good', createdAt: '2026-11-28T10:00:00Z' }));

    expect(await getMyRating('t1')).toMatchObject({ score: 4, comment: 'Good' });
  });

  it('sends the score and a trimmed comment', async () => {
    fetchMock.mockResolvedValueOnce(json(201, { bookingId: 't1', score: 5, comment: 'Great', createdAt: 'x' }));

    await submitRating('t1', 5, '  Great  ');

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/bookings/t1/rating');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual({ score: 5, comment: 'Great' });
  });

  it('leaves the comment out when it is blank', async () => {
    fetchMock.mockResolvedValueOnce(json(201, { bookingId: 't1', score: 3, comment: null, createdAt: 'x' }));

    await submitRating('t1', 3, '   ');

    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ score: 3 });
  });

  it('surfaces the error code when a rating is final', async () => {
    fetchMock.mockResolvedValueOnce(json(409, { code: 'ALREADY_RATED' }));

    await expect(submitRating('t1', 1, '')).rejects.toMatchObject({ status: 409, code: 'ALREADY_RATED' });
  });

  it('reads my overall rating', async () => {
    fetchMock.mockResolvedValueOnce(json(200, { average: 4.33, count: 3 }));

    expect(await getMyRatingSummary()).toEqual({ average: 4.33, count: 3 });
    expect(fetchMock.mock.calls[0][0]).toBe('/api/ratings/me');
  });
});
