// Ambient types for the contextBridge surface exposed by app/electron/preload.ts.
// Imports the renderer-safe contract (not the preload module itself) so no
// electron/node code is pulled into the renderer program.
import type { DotaRecBridge } from '../electron/bridge-contract';

declare global {
  interface Window {
    readonly dotarec?: DotaRecBridge;
  }

  // Inlined by Vite at build time from app/package.json (see vite.config.ts `define`).
  const __APP_VERSION__: string;
}

export {};
