import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { env } from 'node:process';
import { developmentProxy } from './dev-proxy.ts';
export default defineConfig({
  plugins: [react()], base: '/app/',
  build: { outDir: 'dist', emptyOutDir: true, sourcemap: false },
  server: { host: '127.0.0.1', port: 5173, strictPort: true,
    proxy: developmentProxy(env['SCHEDULING_DEV_BACKEND'] ?? 'http://127.0.0.1:8080') },
  test: { environment: 'jsdom', setupFiles: ['./src/test-setup.ts'], restoreMocks: true, clearMocks: true },
});
