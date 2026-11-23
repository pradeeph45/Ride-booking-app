import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { getAccessToken, onSessionChange, refreshSession } from './api';

interface AuthState {
  /** true once the first silent refresh (cookie -> access token) has finished */
  ready: boolean;
  signedIn: boolean;
}

const AuthContext = createContext<AuthState>({ ready: false, signedIn: false });

export function AuthProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(false);
  const [signedIn, setSignedIn] = useState(getAccessToken() !== null);

  useEffect(() => {
    const unsubscribe = onSessionChange(() => setSignedIn(getAccessToken() !== null));
    // After a page reload the access token is gone; the HttpOnly cookie can mint a new one.
    refreshSession().finally(() => setReady(true));
    return () => {
      unsubscribe();
    };
  }, []);

  return <AuthContext.Provider value={{ ready, signedIn }}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
