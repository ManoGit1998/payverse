/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The SPA talks to the API gateway only. In dev, Vite proxies /api to it, so the browser sees one
// origin (no CORS) — set GATEWAY_URL to point elsewhere. Grafana already uses :3000, so the app runs on :5173.
// The browser still sends Origin: http://localhost:5173, which the gateway's CORS filter rejects (403) unless listed in
// CORS_ALLOWED_ORIGINS. Through the proxy the app is same-origin, so drop the header instead.
const gateway = {
  target: process.env.GATEWAY_URL ?? 'http://localhost:8080',
  changeOrigin: true,
  configure: (proxy: { on(event: 'proxyReq', listener: (req: { removeHeader(name: string): void }) => void): void }) =>
    proxy.on('proxyReq', (req) => req.removeHeader('origin')),
};

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': gateway,
    },
  },
  preview: {
    port: 4173,
    proxy: {
      '/api': gateway,
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
