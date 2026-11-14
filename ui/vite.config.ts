import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// `npm run dev` proxies /api to a locally running forge server (./gradlew :server:run --args=8787).
export default defineConfig({
  plugins: [react()],
  build: { outDir: 'dist', sourcemap: false },
  server: { host: '127.0.0.1', proxy: { '/api': 'http://127.0.0.1:8787' } },
});
