import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const backendPort = process.env.VITE_BACKEND_PORT || '8080';
const frontendPort = parseInt(process.env.VITE_PORT || '5173', 10);

export default defineConfig({
  plugins: [react()],
  server: {
    port: frontendPort,
    proxy: {
      // Use 127.0.0.1 (not localhost) so the proxy targets IPv4 — Node may resolve
      // 'localhost' to the IPv6 loopback ::1, which the backend (IPv4) does not bind.
      '/api': `http://127.0.0.1:${backendPort}`,
      '/ws': { target: `ws://127.0.0.1:${backendPort}`, ws: true }
    }
  }
});
