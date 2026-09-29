import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// /binarium → публічний API графіка Binarium (обхід CORS у dev і preview).
const binarium = {
  '/binarium': {
    target: 'https://binarium.com',
    changeOrigin: true,
    rewrite: (p) => p.replace(/^\/binarium/, ''),
  },
};

export default defineConfig({
  plugins: [react()],
  server: { proxy: binarium },
  preview: { proxy: binarium },
});
