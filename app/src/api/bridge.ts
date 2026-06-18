// Shared contract for the contextBridge surface the Electron preload exposes to
// the renderer. Renderer-safe by design: NO electron/node imports, so it can be
// referenced by both app/electron/preload.ts (main process) and the renderer's
// ambient window typing without dragging main-process code into the browser bundle.
export interface DotaRecBridge {
  readonly bridgeBase: string;
  readonly healthUrl: string;
  readonly wsUrl: string;
}
