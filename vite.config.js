import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// /binarium → API цін Binarium, яким користується їхній термінал (обхід CORS у dev і preview).
const binarium = {
  '/binarium': {
    target: 'https://api.binarium.com',
    changeOrigin: true,
    rewrite: (p) => p.replace(/^\/binarium/, ''),
  },
};

export default defineConfig({
  plugins: [react()],
  server: { proxy: binarium },
  preview: { proxy: binarium },
});
