// Contract for the contextBridge surface the Electron preload exposes to the
// renderer. A pure type with NO electron/node imports, so it can be referenced
// type-only by both the preload (producer) and the renderer's window typing
// (consumer) without dragging main-process code into the browser bundle.
export interface DotaRecBridge {
  readonly bridgeBase: string;
  readonly healthUrl: string;
  readonly wsUrl: string;
  // Per-launch shared secret the renderer must send on every bridge request (REST
  // header / WS query param). Empty string when running outside Electron (plain
  // browser dev), in which case the core also runs with auth disabled.
  readonly bridgeToken: string;
}
