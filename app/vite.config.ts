import { readFileSync } from 'node:fs';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The app version, read from app/package.json at build time (kept in lockstep with the root
// package.json, which electron-builder and the core jar already derive theirs from). Inlined into
// the bundle as __APP_VERSION__ so the sidebar shows the real version — a hardcoded label sat at
// v0.1.5 for three releases before anyone noticed.
const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as {
  version: string;
};

// Renderer build config.
// base: './' makes asset URLs relative so the bundle loads correctly over file://
// when Electron opens app/dist/index.html in a packaged build.
export default defineConfig({
  plugins: [react()],
  base: './',
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
  },
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
