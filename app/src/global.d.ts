// Ambient types for the contextBridge surface exposed by app/electron/preload.ts.
// Imports the renderer-safe contract (not the preload module itself) so no
// electron/node code is pulled into the renderer program.
import type { DotaRecBridge } from '../electron/bridge-contract';

declare global {
  interface Window {
    readonly dotarec?: DotaRecBridge;
  }
}

export {};
