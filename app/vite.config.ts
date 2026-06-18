import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Renderer build config.
// base: './' makes asset URLs relative so the bundle loads correctly over file://
// when Electron opens app/dist/index.html in a packaged build.
export default defineConfig({
  plugins: [react()],
  base: './',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    target: 'chrome130',
  },
  server: {
    port: 5173,
    strictPort: true,
  },
});
