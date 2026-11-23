import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, clearSession, getAccessToken, googleLogin, login, refreshSession } from './api';

function json(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('api client', () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    clearSession();
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  it('keeps the access token in memory only after login', async () => {
    const storage = { setItem: vi.fn(), getItem: vi.fn(), removeItem: vi.fn() };
    vi.stubGlobal('localStorage', storage);
    vi.stubGlobal('sessionStorage', storage);
    fetchMock.mockResolvedValueOnce(json(200, { accessToken: 'AT', expiresIn: 899 }));

    await login('a@b.com', '123456');

    expect(getAccessToken()).toBe('AT');
    expect(storage.setItem).not.toHaveBeenCalled(); // never persisted where XSS could read it
    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers['X-RB-CSRF']).toBe('1'); // required by the gateway's CSRF rule
  });

  it('signs in with a Google ID token through the BFF, sending the CSRF header and chosen role', async () => {
    fetchMock.mockResolvedValueOnce(json(200, { accessToken: 'GAT', expiresIn: 899 }));

    await googleLogin('GOOGLE.ID.TOKEN', 'DRIVER');

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/bff/google');
    expect(init.headers['X-RB-CSRF']).toBe('1');
    expect(JSON.parse(init.body)).toEqual({ idToken: 'GOOGLE.ID.TOKEN', role: 'DRIVER' });
    expect(getAccessToken()).toBe('GAT');
  });

  it('surfaces a rejected Google token as a 401 ApiError and stays signed out', async () => {
    fetchMock.mockResolvedValueOnce(json(401, { code: 'INVALID_CREDENTIALS' }));

    await expect(googleLogin('forged', 'RIDER')).rejects.toMatchObject({ status: 401, code: 'INVALID_CREDENTIALS' });
    expect(getAccessToken()).toBeNull();
  });

  it('shares one refresh request between concurrent callers (single-flight)', async () => {
    fetchMock.mockImplementation(async () => {
      await new Promise((r) => setTimeout(r, 10));
      return json(200, { accessToken: 'NEW', expiresIn: 899 });
    });

    const results = await Promise.all([refreshSession(), refreshSession(), refreshSession()]);

    expect(results).toEqual([true, true, true]);
    expect(fetchMock).toHaveBeenCalledTimes(1); // rotation would reject a second parallel refresh
    expect(getAccessToken()).toBe('NEW');
  });

  it('on 401 refreshes once and retries the original request', async () => {
    fetchMock
      .mockResolvedValueOnce(json(200, { accessToken: 'OLD', expiresIn: 1 })) // login
      .mockResolvedValueOnce(json(401)) // expired token
      .mockResolvedValueOnce(json(200, { accessToken: 'FRESH', expiresIn: 899 })) // refresh
      .mockResolvedValueOnce(json(200, { email: 'a@b.com' })); // retry
    await login('a@b.com', '123456');

    const me = await api<{ email: string }>('/api/users/me');

    expect(me.email).toBe('a@b.com');
    expect(fetchMock.mock.calls[3][1].headers.Authorization).toBe('Bearer FRESH');
  });

  it('clears the session when refresh fails', async () => {
    fetchMock
      .mockResolvedValueOnce(json(200, { accessToken: 'OLD', expiresIn: 1 }))
      .mockResolvedValueOnce(json(401))
      .mockResolvedValueOnce(json(401));
    await login('a@b.com', '123456');

    await expect(api('/api/users/me')).rejects.toMatchObject({ status: 401 });
    expect(getAccessToken()).toBeNull();
  });
});
