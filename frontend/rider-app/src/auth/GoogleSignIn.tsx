import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError, googleLogin } from './api';

const CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID;
const SCRIPT_SRC = 'https://accounts.google.com/gsi/client';

function loadGoogleScript(): Promise<void> {
  return new Promise((resolve, reject) => {
    if (window.google?.accounts?.id) return resolve();
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${SCRIPT_SRC}"]`);
    const script = existing ?? Object.assign(document.createElement('script'), { src: SCRIPT_SRC, async: true });
    script.addEventListener('load', () => resolve());
    script.addEventListener('error', () => reject(new Error('Could not load Google sign-in')));
    if (!existing) document.head.appendChild(script);
  });
}

/**
 * Google's own button. It hands us an ID token (a signed proof of who the user is); we forward it to our
 * server, which verifies it with Google's keys. The role is used only if the account is created now.
 */
export default function GoogleSignIn({ role }: { role: 'RIDER' | 'DRIVER' }) {
  const navigate = useNavigate();
  const holder = useRef<HTMLDivElement>(null);
  const roleRef = useRef(role);
  const [error, setError] = useState<string | null>(null);

  roleRef.current = role; // the callback below is registered once; it reads the latest role from here

  useEffect(() => {
    if (!CLIENT_ID) return;
    let cancelled = false;
    loadGoogleScript()
      .then(() => {
        if (cancelled || !holder.current || !window.google) return;
        window.google.accounts.id.initialize({
          client_id: CLIENT_ID,
          callback: async ({ credential }) => {
            setError(null);
            try {
              await googleLogin(credential, roleRef.current);
              navigate('/', { replace: true });
            } catch (err) {
              setError(
                err instanceof ApiError && err.status === 401
                  ? 'Google sign-in was not accepted. Please try again.'
                  : 'Google sign-in failed. Please try again.',
              );
            }
          },
        });
        window.google.accounts.id.renderButton(holder.current, { theme: 'outline', size: 'large', width: 340 });
      })
      .catch(() => setError('Could not load Google sign-in.'));
    return () => {
      cancelled = true;
    };
  }, [navigate]);

  if (!CLIENT_ID) {
    return <p className="muted center">Google sign-in is not configured (set VITE_GOOGLE_CLIENT_ID).</p>;
  }
  return (
    <div className="google">
      <div ref={holder} />
      {error && <p role="alert" className="error">{error}</p>}
    </div>
  );
}
