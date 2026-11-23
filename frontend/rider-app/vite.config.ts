import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// In development the browser talks only to Vite (same origin); /api and /bff are proxied to the
// gateway. Same-origin keeps the refresh cookie first-party and avoids CORS in dev.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/bff': 'http://localhost:8080',
    },
  },
  test: { environment: 'node' },
});
