// Auto-update policy, extracted from main.ts so it can be unit-tested without Electron or
// electron-updater. main.ts wires the real side effects (fire electron-updater's check /
// download / quitAndInstall, poll the core's /status recording gate, push state to the
// renderer) as UpdateControllerDeps; this module owns the state machine, the check cadence,
// and the "never download or install while a match is recording" gating.
//
// Mirrors the SupervisionController shape: a pure class with injected effects. The
// electron-updater adapter (updater.ts) translates its events into the on*() calls here.
import type { UpdateState } from './bridge-contract';

/** A cancel handle returned by {@link UpdateControllerDeps.schedule}. */
export type Cancel = () => void;

export interface UpdateControllerDeps {
  /** Fire electron-updater's checkForUpdates. Results arrive via the on*() callbacks. */
  triggerCheck(): void;
  /** Fire electron-updater's downloadUpdate. Progress/finish arrive via on*() callbacks. */
  triggerDownload(): void;
  /**
   * True when a match is recording (or arming/stopping) — the core's /status gate. MUST
   * fail-safe to `true` (busy) on any error/timeout so an unreachable core never lets an
   * install interrupt a recording.
   */
  isBusy(): Promise<boolean>;
  /**
   * Tear down the supervisors and quitAndInstall. The process exits; this never returns
   * normally. Only ever called after an isBusy() === false check.
   */
  doInstall(): void;
  /** Push the latest state to the renderer and cache it for getState(). */
  emit(state: UpdateState): void;
  /** Append a line to the electron log. */
  log(message: string): void;
  /** Whether background auto-update is enabled (the autoUpdate pref). */
  isEnabled(): boolean;
  /** Schedule fn after `ms`, returning a cancel handle (injected so tests control time). */
  schedule(fn: () => void, ms: number): Cancel;
}

export interface UpdateControllerOptions {
  /** Delay before the first check after boot (default 2 min). */
  readonly firstCheckMs?: number;
  /** Interval between subsequent checks (default 4 h). */
  readonly periodicMs?: number;
  /** Retry delay when a download is deferred because a match is recording (default 5 min). */
  readonly busyRetryMs?: number;
}

const MIN = 60_000;

/**
 * Owns the update state machine:
 *   idle → checking → available → downloading(%) → downloaded → (install | error)
 * with `recording` flagged when a download/install is deferred by the recording gate.
 *
 * Download and install are BOTH gated on "not recording" (fail-safe to busy). A downloaded
 * update waits for the user to click "Restart to update"; it is never installed automatically
 * on quit (autoInstallOnAppQuit stays false in the adapter), so a background quit never
 * interrupts a match.
 */
export class UpdateController {
  private snapshot: UpdateState = { status: 'idle' };
  // Guards against issuing a second downloadUpdate for the same available update (e.g. a
  // retry firing after the download already started).
  private downloading = false;
  // Latched once an install is committed, so rapid "Restart to update" clicks can't each run a
  // supervisor teardown / spawn a second NSIS installer. Cleared only on a recording-deferral
  // (where the user will retry after the match); once doInstall runs it stays latched — the
  // process is exiting.
  private installing = false;
  private cancelCheck: Cancel | null = null;
  private cancelRetry: Cancel | null = null;
  private started = false;

  private readonly firstCheckMs: number;
  private readonly periodicMs: number;
  private readonly busyRetryMs: number;

  constructor(
    private readonly deps: UpdateControllerDeps,
    opts: UpdateControllerOptions = {},
  ) {
    this.firstCheckMs = opts.firstCheckMs ?? 2 * MIN;
    this.periodicMs = opts.periodicMs ?? 4 * 60 * MIN;
    this.busyRetryMs = opts.busyRetryMs ?? 5 * MIN;
  }

  /** The last emitted state (what the renderer polls via updates:getState). */
  getState(): UpdateState {
    return this.snapshot;
  }

  /** Arm the first check and the periodic cadence. Idempotent. */
  start(): void {
    if (this.started) return;
    this.started = true;
    this.armCheck(this.firstCheckMs);
  }

  /**
   * Manual check (Settings "Check for updates"): always runs, even when auto-update is
   * disabled, so the user can see whether an update exists. Returns the snapshot after the
   * check starts (status 'checking'); the real result lands via the on*() callbacks + emit.
   */
  check(): UpdateState {
    // Don't disturb an in-progress download or a ready-to-install update with a fresh check.
    if (this.snapshot.status === 'downloading' || this.snapshot.status === 'downloaded') {
      return this.snapshot;
    }
    this.set({ status: 'checking' });
    this.deps.triggerCheck();
    return this.snapshot;
  }

  /**
   * Install a downloaded update now (Settings "Restart to update"). Re-checks the recording
   * gate first: while a match is recording it refuses and re-emits with recording:true so the
   * UI shows the deferral copy. Otherwise it hands off to doInstall(), which exits the process.
   */
  async installNow(): Promise<void> {
    if (this.installing) return;
    if (this.snapshot.status !== 'downloaded') return;
    // Latch synchronously BEFORE the await so two near-simultaneous clicks can't both pass the
    // busy check and both call doInstall (concurrent teardowns / a duplicate NSIS installer).
    this.installing = true;
    let busy: boolean;
    try {
      busy = await this.deps.isBusy();
    } catch (err) {
      // isBusy is contracted to fail-safe to true and never reject; if it somehow does, release the
      // latch so "Restart to update" stays clickable instead of wedging dead for the whole session,
      // and fail safe by NOT installing (an unknown gate is treated as busy).
      this.installing = false;
      this.deps.log(`[updater] install gate check threw; not installing: ${String(err)}`);
      return;
    }
    if (busy) {
      // Not installing after all — release the latch so the user can retry once the match ends.
      this.installing = false;
      this.deps.log('[updater] install requested while recording; deferring');
      this.set({ ...this.snapshot, recording: true });
      // Poll the recording gate so the "can't restart now" flag clears once the match ends,
      // rather than sticking forever. We do NOT auto-install (Option 2 is user-confirmed): the
      // UI just returns to a plain "Restart to update" for the user to click again.
      this.clearRetry();
      this.cancelRetry = this.deps.schedule(() => void this.clearInstallDeferral(), this.busyRetryMs);
      return;
    }
    this.deps.log('[updater] installing downloaded update');
    this.deps.doInstall();
  }

  /**
   * React to an autoUpdate pref change. Enabling re-checks (an already-available update then starts
   * downloading). Disabling drops a deferred-download retry and its stale "download will start once
   * you're not recording" promise — a fully downloaded update is left installable regardless.
   */
  onAutoUpdateChanged(enabled: boolean): void {
    if (enabled) {
      this.check();
      return;
    }
    if (this.snapshot.status === 'available') {
      this.clearRetry();
      if (this.snapshot.recording) {
        this.set({
          status: 'available',
          ...(this.snapshot.version !== undefined ? { version: this.snapshot.version } : {}),
          ...(this.snapshot.notesUrl !== undefined ? { notesUrl: this.snapshot.notesUrl } : {}),
        });
      }
    }
  }

  /**
   * While an install is deferred by a live match, re-poll the gate; once it clears, drop the
   * recording flag so the stale "can't restart" note disappears. Keeps polling while still busy.
   */
  private async clearInstallDeferral(): Promise<void> {
    if (this.snapshot.status !== 'downloaded') return;
    if (await this.deps.isBusy()) {
      this.cancelRetry = this.deps.schedule(() => void this.clearInstallDeferral(), this.busyRetryMs);
      return;
    }
    if (this.snapshot.recording) {
      // Re-emit the plain downloaded snapshot (recording flag dropped).
      this.set({
        status: 'downloaded',
        ...(this.snapshot.version !== undefined ? { version: this.snapshot.version } : {}),
        ...(this.snapshot.notesUrl !== undefined ? { notesUrl: this.snapshot.notesUrl } : {}),
      });
    }
  }

  // --- electron-updater event sinks (called by the adapter in updater.ts) ---

  onCheckingForUpdate(): void {
    this.set({ status: 'checking' });
  }

  onUpdateAvailable(version: string, notesUrl: string): void {
    this.deps.log(`[updater] update available: ${version}`);
    this.set({ status: 'available', version, notesUrl });
    // Auto-download only when enabled; a disabled pref leaves it at 'available' so the UI can
    // surface it without pulling ~320 MB in the background.
    if (this.deps.isEnabled()) void this.tryDownload();
  }

  onUpdateNotAvailable(): void {
    this.set({ status: 'not-available' });
  }

  onDownloadProgress(percent: number): void {
    // Ignore late progress after a downloaded/errored terminal state.
    if (this.snapshot.status !== 'downloading') return;
    this.set({ ...this.snapshot, percent });
  }

  onUpdateDownloaded(version: string): void {
    this.downloading = false;
    this.clearRetry();
    this.deps.log(`[updater] update downloaded: ${version}`);
    this.set({
      status: 'downloaded',
      version,
      // Carry the notes URL forward from the 'available' snapshot when we have it.
      ...(this.snapshot.notesUrl !== undefined ? { notesUrl: this.snapshot.notesUrl } : {}),
    });
  }

  onError(message: string): void {
    this.downloading = false;
    this.deps.log(`[updater] error: ${message}`);
    this.set({ status: 'error', error: message });
  }

  // --- internals ---

  /** Try to download the available update, deferring (and retrying) while recording. */
  private async tryDownload(): Promise<void> {
    if (this.downloading) return;
    if (this.snapshot.status !== 'available') return;
    // Respect the pref here too (not just at the onUpdateAvailable call site): a retry armed
    // while recording could otherwise fire AFTER the user disabled auto-update and pull the full
    // update in the background. clearRetry() drops the now-moot deferral timer.
    if (!this.deps.isEnabled()) {
      this.clearRetry();
      return;
    }
    // Claim the download slot BEFORE the await so two concurrent callers (e.g. a retry racing a
    // fresh onUpdateAvailable) can't both pass the guard and each fire triggerDownload — the
    // second sees downloading===true and bails. Released again on every early-return below.
    this.downloading = true;
    const busy = await this.deps.isBusy();
    // The pref may have been flipped off during the isBusy() await; re-check before committing.
    if (!this.deps.isEnabled()) {
      this.downloading = false;
      this.clearRetry();
      return;
    }
    if (busy) {
      this.downloading = false;
      this.deps.log('[updater] update available but recording; deferring download');
      this.set({ ...this.snapshot, recording: true });
      this.clearRetry();
      this.cancelRetry = this.deps.schedule(() => void this.tryDownload(), this.busyRetryMs);
      return;
    }
    this.clearRetry();
    this.set({
      status: 'downloading',
      percent: 0,
      ...(this.snapshot.version !== undefined ? { version: this.snapshot.version } : {}),
      ...(this.snapshot.notesUrl !== undefined ? { notesUrl: this.snapshot.notesUrl } : {}),
    });
    this.deps.triggerDownload();
  }

  /** Arm a check after `ms`; on fire it (conditionally) checks then re-arms at the periodic cadence. */
  private armCheck(ms: number): void {
    this.cancelCheck?.();
    this.cancelCheck = this.deps.schedule(() => {
      // Scheduled (background) checks respect the pref; the manual check() bypasses this.
      if (this.deps.isEnabled()) this.check();
      this.armCheck(this.periodicMs);
    }, ms);
  }

  private clearRetry(): void {
    this.cancelRetry?.();
    this.cancelRetry = null;
  }

  private set(next: UpdateState): void {
    this.snapshot = next;
    this.deps.emit(next);
  }
}
