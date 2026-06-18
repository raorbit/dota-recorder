// Ambient types for the contextBridge surface exposed by app/electron/preload.ts.
import type { DotaRecBridge } from '../electron/preload';

declare global {
  interface Window {
    readonly dotarec?: DotaRecBridge;
  }
}

export {};
