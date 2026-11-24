// All backend calls go through here. The access token lives ONLY in this module's memory (never
// localStorage/sessionStorage), so a page reload loses it and the HttpOnly refresh cookie restores it.

export class ApiError extends Error {
  constructor(
    public status: number,
    public code?: string,
  ) {
    super(code ?? `HTTP ${status}`);
  }
}

export interface UserProfile {
  id: string;
  email: string;
  role: string;
  createdAt: string;
}

export interface DeviceSession {
  id: string;
  deviceName: string | null;
  ipAddress: string | null;
  createdAt: string;
  lastSeenAt: string;
  current: boolean;
}

// The gateway rejects /bff calls without this header: a cross-site form cannot send it.
const CSRF = { 'X-RB-CSRF': '1' };

let accessToken: string | null = null;
let refreshing: Promise<boolean> | null = null;
const listeners = new Set<() => void>();

export const getAccessToken = () => accessToken;
export const onSessionChange = (fn: () => void) => {
  listeners.add(fn);
  return () => listeners.delete(fn);
};

function setAccessToken(token: string | null) {
  accessToken = token;
  listeners.forEach((fn) => fn());
}

export function clearSession() {
  setAccessToken(null);
  refreshing = null;
}

async function readError(res: Response): Promise<ApiError> {
  let code: string | undefined;
  try {
    code = (await res.json()).code;
  } catch {
    /* body is empty or not JSON */
  }
  return new ApiError(res.status, code);
}

async function post(path: string, body?: unknown, extra: Record<string, string> = {}): Promise<Response> {
  return fetch(path, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...extra },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

export async function register(email: string, password: string, role: 'RIDER' | 'DRIVER'): Promise<UserProfile> {
  const res = await post('/api/auth/register', { email, password, role });
  if (!res.ok) throw await readError(res);
  return res.json();
}

/** Always succeeds (202) even for unknown emails, by design: no account enumeration. */
export async function requestOtp(email: string): Promise<void> {
  const res = await post('/api/auth/otp/request', { email });
  if (!res.ok) throw await readError(res);
}

export async function login(email: string, otp: string): Promise<void> {
  const res = await fetch('/bff/login', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...CSRF },
    body: JSON.stringify({ email, otp }),
  });
  if (!res.ok) throw await readError(res);
  setAccessToken((await res.json()).accessToken);
}

/**
 * Sign in with Google. `idToken` is the credential from Google's button; the server verifies it (we never
 * trust it here). `role` only matters if this Google account is new to us.
 */
export async function googleLogin(idToken: string, role: 'RIDER' | 'DRIVER'): Promise<void> {
  const res = await fetch('/bff/google', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...CSRF },
    body: JSON.stringify({ idToken, role }),
  });
  if (!res.ok) throw await readError(res);
  setAccessToken((await res.json()).accessToken);
}

/**
 * Exchanges the refresh cookie for a new access token. Concurrent callers share ONE request:
 * refresh tokens rotate, so a second parallel call with the same cookie would be treated as reuse.
 */
export function refreshSession(): Promise<boolean> {
  if (!refreshing) {
    refreshing = (async () => {
      try {
        const res = await fetch('/bff/refresh', { method: 'POST', credentials: 'include', headers: CSRF });
        if (!res.ok) {
          setAccessToken(null);
          return false;
        }
        setAccessToken((await res.json()).accessToken);
        return true;
      } catch {
        return false;
      } finally {
        refreshing = null;
      }
    })();
  }
  return refreshing;
}

export async function logout(): Promise<void> {
  try {
    await fetch('/bff/logout', {
      method: 'POST',
      credentials: 'include',
      headers: { ...CSRF, ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}) },
    });
  } finally {
    clearSession();
  }
}

/** Authenticated JSON call. On 401 it refreshes once and retries; if that fails the user is signed out. */
export async function api<T>(path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
  const call = () =>
    fetch(path, {
      method: init.method ?? (init.body === undefined ? 'GET' : 'POST'),
      credentials: 'include',
      headers: {
        Authorization: `Bearer ${accessToken ?? ''}`,
        ...(init.body === undefined ? {} : { 'Content-Type': 'application/json' }),
      } as Record<string, string>,
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
    });

  let res = await call();
  if (res.status === 401 && (await refreshSession())) {
    res = await call();
  }
  if (res.status === 401) clearSession();
  if (!res.ok) throw await readError(res);
  return res.status === 204 ? (undefined as T) : res.json();
}
