// Dev-only: renders the real app against canned data. See /preview.html.
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import App from '../App';
import { AuthProvider } from '../auth/AuthContext';
import '../styles.css';
import { installMockApi } from './mockApi';

const params = new URLSearchParams(window.location.search);
installMockApi({ role: params.get('role') === 'DRIVER' ? 'DRIVER' : 'RIDER', empty: params.get('empty') === '1', anon: params.get('anon') === '1' });

// ?submit=1 presses the first form's button after load (e.g. to show the fare options on /book)
if (params.get('submit') === '1') {
  setTimeout(() => document.querySelector('form')?.requestSubmit(), 600);
}

// ?email=a@b.com prefills the login form (as after registering)
const route = params.get('route') ?? '/';
const entry = params.get('email') ? { pathname: route, state: { email: params.get('email'), registered: params.get('registered') === '1' } } : route;

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <MemoryRouter initialEntries={[entry]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>
  </StrictMode>,
);
