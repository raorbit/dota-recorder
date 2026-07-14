// Contract for the contextBridge surface the Electron preload exposes to the
// renderer. A pure type with NO electron/node imports, so it can be referenced
// type-only by both the preload (producer) and the renderer's window typing
// (consumer) without dragging main-process code into the browser bundle.

// Snapshot of the app-update lifecycle the main process pushes to the renderer.
// `status` drives the update UI; the other fields populate as the flow advances.
// Shared verbatim by the main-process UpdateController (its own state) and the
// renderer store, so the wire shape can never drift between producer and consumer.
export interface UpdateState {
  readonly status:
    | 'idle'
    | 'checking'
    | 'available'
    | 'downloading'
    | 'downloaded'
    | 'not-available'
    | 'error';
  // The available/downloaded version (no leading "v"), when known.
  readonly version?: string;
  // 0..100 while status === 'downloading'.
  readonly percent?: number;
  // GitHub release-notes URL for `version`, when known.
  readonly notesUrl?: string;
  // Human-readable message when status === 'error'.
  readonly error?: string;
  // True when a download or install is being held back because a match is recording.
  readonly recording?: boolean;
}

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
  // Reveals a file in the OS file manager (Explorer), selecting it. Backs the recording
  // right-click "Reveal in folder" action. A blank/missing path is a no-op. The renderer
  // is sandboxed and has no shell access, so this round-trips to the main process.
  readonly revealPath: (path: string) => Promise<void>;
  // Window controls for the frameless window's custom title bar (WindowFrame).
  // Close hides to the tray, not quits — it goes through the same close handler
  // as the (removed) native X.
  readonly minimizeWindow: () => Promise<void>;
  readonly maximizeToggleWindow: () => Promise<void>;
  readonly closeWindow: () => Promise<void>;
  // App-update pref the main process owns: gates whether electron-updater downloads
  // in the background. Both resolve the effective value the main process stored.
  readonly getAutoUpdate: () => Promise<boolean>;
  readonly setAutoUpdate: (value: boolean) => Promise<boolean>;
  // Update lifecycle. getUpdateState returns the current snapshot; checkForUpdates
  // kicks off a manual check (resolving the snapshot after it starts — real results
  // arrive via onUpdateState); installUpdateNow quits-and-installs a downloaded update
  // (no-op, surfaced as recording:true, while a match is recording).
  readonly getUpdateState: () => Promise<UpdateState>;
  readonly checkForUpdates: () => Promise<UpdateState>;
  readonly installUpdateNow: () => Promise<void>;
  // Main → renderer push of update-state transitions. Returns an unsubscribe fn for
  // React effect cleanup (ipcRenderer.on otherwise leaks a listener per remount).
  readonly onUpdateState: (cb: (state: UpdateState) => void) => () => void;
  // Opens the GitHub release-notes page for a version in the OS browser. The main
  // process constructs the URL itself, preserving the window's navigation lockdown.
  readonly openReleaseNotes: (version?: string) => Promise<void>;
}
