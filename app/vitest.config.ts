import { defineConfig } from 'vitest/config';

// Unit tests for the Electron main-process supervisors. They run in Node and mock electron / node
// built-ins, so no jsdom or vite-react pipeline is needed. The renderer (src/) is intentionally not
// covered here.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['electron/**/*.test.ts'],
  },
});
