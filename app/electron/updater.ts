// electron-updater adapter: the thin, Electron-coupled layer that owns the `autoUpdater`
// singleton and translates its events into plain callbacks the (testable) UpdateController
// consumes. Mirrors the jvm-supervisor/obs-supervisor split: side-effectful glue here,
// policy in update-controller.ts.
//
// Configured for the plan's Option 2: autoDownload=false (the controller decides when, gated
// on "not recording") and autoInstallOnAppQuit=false (a downloaded update is NEVER installed
// on a background quit — only via an explicit, recording-checked quitAndInstall). No
// publisherName is ever set: this is an unsigned app and signature verification must stay off
// (see plans/autoupdate-investigation.md — "start unsigned, stay unsigned").
import { autoUpdater } from 'electron-updater';

// The GitHub repo the release notes live on (matches electron-builder.yml `publish`).
const REPO = 'raorbit/dota-recorder';

/** GitHub release-notes URL for a version (tag `v<version>`), or the releases index. */
export function releaseNotesUrl(version?: string): string {
  const base = `https://github.com/${REPO}/releases`;
  return version ? `${base}/tag/v${version}` : base;
}

export interface UpdaterHooks {
  onChecking(): void;
  onAvailable(version: string, notesUrl: string): void;
  onNotAvailable(): void;
  onProgress(percent: number): void;
  onDownloaded(version: string): void;
  onError(message: string): void;
}

export interface UpdaterHandle {
  /** Start a check; a rejection is routed to hooks.onError (electron-updater also emits 'error'). */
  check(): void;
  /** Start the download of an available update. */
  download(): void;
  /** Silently install the downloaded update and relaunch. Exits the process. */
  quitAndInstall(): void;
}

/**
 * Wire electron-updater to the given hooks and return the control surface. Call ONLY in a
 * packaged build (electron-updater needs app-update.yml, absent in dev). `log` receives the
 * updater's own diagnostic stream, routed into the app's existing electron.log.
 */
export function initUpdater(hooks: UpdaterHooks, log: (line: string) => void): UpdaterHandle {
  autoUpdater.autoDownload = false;
  autoUpdater.autoInstallOnAppQuit = false;
  autoUpdater.logger = {
    info: (m) => log(`[updater] ${String(m)}`),
    warn: (m) => log(`[updater] WARN ${String(m)}`),
    error: (m) => log(`[updater] ERROR ${String(m)}`),
    debug: (m) => log(`[updater] ${String(m)}`),
  };

  autoUpdater.on('checking-for-update', () => hooks.onChecking());
  autoUpdater.on('update-available', (info) =>
    hooks.onAvailable(info.version, releaseNotesUrl(info.version)),
  );
  autoUpdater.on('update-not-available', () => hooks.onNotAvailable());
  autoUpdater.on('download-progress', (progress) =>
    hooks.onProgress(Math.round(progress.percent)),
  );
  autoUpdater.on('update-downloaded', (event) => hooks.onDownloaded(event.version));
  autoUpdater.on('error', (err) => hooks.onError(err instanceof Error ? err.message : String(err)));

  return {
    check: () => {
      autoUpdater
        .checkForUpdates()
        .catch((err: unknown) =>
          hooks.onError(err instanceof Error ? err.message : String(err)),
        );
    },
    download: () => {
      autoUpdater
        .downloadUpdate()
        .catch((err: unknown) =>
          hooks.onError(err instanceof Error ? err.message : String(err)),
        );
    },
    // isSilent=true runs the NSIS installer with /S (no assisted UI); isForceRunAfter=true
    // relaunches the app after install.
    quitAndInstall: () => autoUpdater.quitAndInstall(true, true),
  };
}
