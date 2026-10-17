/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The SPA talks to the API gateway only. In dev, Vite proxies /api to it, so the browser sees one
// origin (no CORS) — set GATEWAY_URL to point elsewhere. Grafana already uses :3000, so the app runs on :5173.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.GATEWAY_URL ?? 'http://localhost:8080', changeOrigin: true },
    },
  },
  preview: {
    port: 4173,
    proxy: {
      '/api': { target: process.env.GATEWAY_URL ?? 'http://localhost:8080', changeOrigin: true },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
