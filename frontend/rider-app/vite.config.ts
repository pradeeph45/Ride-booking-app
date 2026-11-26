import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// In development the browser talks only to Vite (same origin); /api and /bff are proxied to the
// gateway. Same-origin keeps the refresh cookie first-party and avoids CORS in dev.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Live tracking goes straight to tracking-service: proxying WebSockets through the MVC gateway is unreliable.
      // The more specific rules are listed first so they win over the general /api rule.
      '/ws': { target: 'ws://localhost:8084', ws: true },
      '/api/tracking': { target: 'http://localhost:8084', rewrite: (path) => path.replace('/api/tracking', '/tracking') },
      '/api': 'http://localhost:8080',
      '/bff': 'http://localhost:8080',
    },
  },
  test: { environment: 'node' },
});
