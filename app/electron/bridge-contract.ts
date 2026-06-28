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
  // Opens the native folder picker (main process) and resolves the chosen path,
  // or null if the user cancelled. Used by the recording output-folder Browse button.
  readonly selectFolder: () => Promise<string | null>;
  // App-level (OS) prefs the main process owns. Launch-at-login starts the app hidden
  // in the tray when you sign in to Windows. Both resolve the effective value the main
  // process applied (so the UI stays in sync with the OS).
  readonly getLaunchAtLogin: () => Promise<boolean>;
  readonly setLaunchAtLogin: (value: boolean) => Promise<boolean>;
}
