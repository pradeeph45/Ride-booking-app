import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { api, getAccessToken, onSessionChange, refreshSession, type UserProfile } from './api';

interface AuthState {
  /** true once the first silent refresh (cookie -> access token) has finished */
  ready: boolean;
  signedIn: boolean;
  /** who is signed in (null until loaded); the header uses it to show role-specific navigation */
  profile: UserProfile | null;
}

const AuthContext = createContext<AuthState>({ ready: false, signedIn: false, profile: null });

export function AuthProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(false);
  const [signedIn, setSignedIn] = useState(getAccessToken() !== null);
  const [profile, setProfile] = useState<UserProfile | null>(null);

  useEffect(() => {
    const unsubscribe = onSessionChange(() => setSignedIn(getAccessToken() !== null));
    // After a page reload the access token is gone; the HttpOnly cookie can mint a new one.
    refreshSession().finally(() => setReady(true));
    return () => {
      unsubscribe();
    };
  }, []);

  useEffect(() => {
    if (!signedIn) {
      setProfile(null);
      return;
    }
    let cancelled = false;
    api<UserProfile>('/api/users/me')
      .then((p) => !cancelled && setProfile(p))
      .catch(() => undefined); // pages show their own errors; the header just stays minimal
    return () => {
      cancelled = true;
    };
  }, [signedIn]);

  return <AuthContext.Provider value={{ ready, signedIn, profile }}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
