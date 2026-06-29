import { defineConfig } from 'vitest/config';

// Unit tests run in Node and mock electron / node built-ins (and stub localStorage), so no jsdom or
// vite-react pipeline is needed. Covers the Electron main-process supervisors (electron/) plus the
// renderer's PURE, React-free logic modules (src/lib/ — column/sort/format/prefs helpers). React
// components themselves are intentionally not covered here.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['electron/**/*.test.ts', 'src/**/*.test.ts'],
  },
});
