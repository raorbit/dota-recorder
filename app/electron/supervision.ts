// Crash-supervision policy for the JVM core and OBS, extracted from main.ts so it can be unit-tested
// without Electron. main.ts wires the real side effects (start/stop the supervisors, relaunch OBS,
// tray notice, log) as SupervisionDeps; this module owns the bounded-restart counters, the
// re-entrancy / launch guards, and the crash sequencing.

/** The fields of a child process's 'exit' event the policy cares about (matches the supervisors). */
export interface ChildExit {
  readonly code: number | null;
  readonly signal: NodeJS.Signals | null;
}

export interface SupervisionDeps {
  /** (Re)start the JVM core; MUST reject if it spawns but never reports /health. */
  startCore(): Promise<void>;
  /** Reap the core (graceful then hard), freeing its loopback ports. */
  stopCore(): Promise<void>;
  /** Launch + adopt a fresh OBS supervisor (wires its onUnexpectedExit back to this controller). */
  startObs(): Promise<void>;
  /** Stop + clear the current OBS supervisor (no-op if none). */
  stopObs(): Promise<void>;
  /** Surface a recorder-down condition to a possibly tray-hidden user. */
  notifyDown(message: string): void;
  /** Append a line to the electron log. */
  log(message: string): void;
  /** True once a real quit/shutdown is underway, so a crash callback must no-op. */
  isShuttingDown(): boolean;
}

function formatExit(info: ChildExit): string {
  return `code=${info.code ?? 'null'} signal=${info.signal ?? 'null'}`;
}

/**
 * Owns the crash/restart state machine for the core + OBS. All side effects are injected, so this is
 * fully testable: the bounded restart budgets, the re-entrancy guard that stops a crash-during-restart
 * from running two overlapping {@link SupervisionDeps.startCore}, the launch guard that keeps two
 * concurrent OBS launches from orphaning a process, and the OBS-teardown-then-restart sequencing.
 */
export class SupervisionController {
  private coreRestartAttempts = 0;
  private obsRestartAttempts = 0;
  private coreRestarting = false;
  private obsLaunching = false;

  constructor(
    private readonly deps: SupervisionDeps,
    private readonly maxRestarts = 2,
  ) {}

  /**
   * Serialized OBS (re)launch — reachable from bootstrap and both crash paths. The guard keeps at most
   * one launch in flight, so two near-simultaneous triggers can't each spawn an obs64.exe and orphan
   * the first (the caller only ever holds the latest supervisor reference).
   */
  async launchObs(): Promise<void> {
    if (this.obsLaunching) {
      this.deps.log('[obs] launch already in progress; skipping duplicate request');
      return;
    }
    this.obsLaunching = true;
    try {
      await this.deps.startObs();
    } finally {
      this.obsLaunching = false;
    }
  }

  /** OBS crashed (exited without a stop()). Bounded-relaunch it. */
  handleObsCrash(info: ChildExit): void {
    if (this.deps.isShuttingDown()) return;
    this.deps.log(`[obs] crash detected (${formatExit(info)})`);
    if (this.obsRestartAttempts >= this.maxRestarts) {
      this.deps.log('[obs] exceeded restart attempts; leaving OBS down (recorder will show not ready)');
      return;
    }
    this.obsRestartAttempts += 1;
    this.deps.log(`[obs] relaunching (attempt ${this.obsRestartAttempts}/${this.maxRestarts})`);
    void this.launchObs();
  }

  /**
   * The core crashed. Stop the now-orphaned OBS (nothing drives recording without the core), then
   * bounded-restart the core and relaunch OBS. On a tray-hidden app this is the only thing between a
   * core crash and silently-stopped recording.
   */
  async handleCoreCrash(info: ChildExit): Promise<void> {
    if (this.deps.isShuttingDown()) return;
    if (this.coreRestarting) {
      // A crash landed while a restart is already in flight (e.g. the just-restarted core crashed again
      // during its health wait). Don't run a second overlapping startCore() on the same core.
      this.deps.log('[core] crash during an in-flight restart; a restart is already running');
      return;
    }
    this.coreRestarting = true;
    try {
      this.deps.log(`[core] crash detected (${formatExit(info)})`);
      await this.deps.stopObs();
      if (this.coreRestartAttempts >= this.maxRestarts) {
        this.deps.notifyDown(
          'The recorder core stopped and could not be restarted. Restart the app to resume recording.',
        );
        return;
      }
      this.coreRestartAttempts += 1;
      this.deps.log(`[core] restarting (attempt ${this.coreRestartAttempts}/${this.maxRestarts})`);
      try {
        await this.deps.startCore();
        this.deps.log('[core] restarted; relaunching OBS');
        this.obsRestartAttempts = 0; // a fresh core epoch gets a fresh OBS restart budget
        void this.launchObs();
      } catch (err) {
        this.deps.log(`[core] restart failed: ${err instanceof Error ? err.message : String(err)}`);
        // startCore() does NOT kill its child on a health-timeout, so a restarted-but-unhealthy JVM
        // would linger holding the loopback ports. Reap it before giving up.
        await this.deps.stopCore().catch(() => {});
        this.deps.notifyDown('The recorder core stopped. Restart the app to resume recording.');
      }
    } finally {
      this.coreRestarting = false;
    }
  }
}
