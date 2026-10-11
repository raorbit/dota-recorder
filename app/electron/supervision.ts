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
  /**
   * Launch + adopt a fresh OBS supervisor (wires its onUnexpectedExit back to this controller).
   * Failures are expected to be handled internally (log + leave OBS down, surfaced via /status) —
   * but unlike {@link startCore} this is not a MUST-reject contract, and a rejection is tolerated:
   * {@link SupervisionController.launchObs} logs it and keeps draining any queued relaunch rather
   * than letting it drop the relaunch or escape as an unhandled rejection.
   */
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

/**
 * An OBS that ran this long after its last relaunch before exiting was healthy, so its relaunch budget
 * starts over. Without this the budget never refilled: two OBS restarts in a days-long tray session
 * (the core restarts a wedged OBS on purpose, at most every few minutes) left OBS down for good.
 *
 * The core's heal spacing (ObsHealer.MIN_INTERVAL_MS, Java) must stay longer than this, or each heal's
 * kill lands inside the window, reads as a crash loop and spends this budget. supervision.test.ts parses
 * that Java constant and fails unless it outlasts this window by 30s of headroom.
 *
 * Measured on a monotonic clock (the controller's default `now` is `performance.now()`), never wall
 * time: an NTP correction or manual clock change in a days-long session would otherwise either make a
 * fast crash loop look stable (a forward step: unbounded relaunches) or push the elapsed time negative
 * (a backward step: the budget never refills).
 */
export const OBS_STABLE_MS = 2 * 60_000;

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
  private corePendingRecrash = false;
  private obsLaunching = false;
  private obsRelaunchPending = false;
  private obsGaveUp = false;
  private lastObsRelaunchAt = Number.NEGATIVE_INFINITY;

  /**
   * @param now monotonic milliseconds, only ever compared against itself (see {@link OBS_STABLE_MS});
   *   tests inject a fake clock. The default is wrapped rather than passed as `performance.now`
   *   because Node rejects that method called without its `performance` receiver (ERR_INVALID_THIS).
   */
  constructor(
    private readonly deps: SupervisionDeps,
    private readonly maxRestarts = 2,
    private readonly now: () => number = () => performance.now(),
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
      // Drain loop: a crash of THIS launch's own child fires handleObsCrash while obsLaunching is still
      // true, which can't launch a fresh OBS itself (the guard above blocks it) so it queues the relaunch
      // in obsRelaunchPending. Re-run startObs() once for each queued relaunch. The budget was already
      // charged by handleObsCrash before it queued, so this stays bounded by obsRestartAttempts (a crash
      // past the budget queues nothing) — never an unbounded loop.
      do {
        this.obsRelaunchPending = false;
        try {
          await this.deps.startObs();
        } catch (err) {
          // startObs is expected to handle its own failures internally, but its contract tolerates a
          // rejection. One must not escape this loop: launchObs is fired via `void launchObs()` from
          // the crash paths, so an escaping rejection would be unhandled AND would skip the remaining
          // drain iterations — dropping a queued relaunch whose budget was already charged. Log it and
          // keep draining.
          this.deps.log(`[obs] launch failed: ${err instanceof Error ? err.message : String(err)}`);
        }
      } while (this.obsRelaunchPending);
    } finally {
      this.obsLaunching = false;
      this.obsRelaunchPending = false; // bound the flag's lifetime to this one launch episode
    }
  }

  /** A launch or crash relaunch is in flight. */
  isObsLaunching(): boolean {
    return this.obsLaunching;
  }

  /** Whether OBS crash-looped through its relaunch budget and was left down. */
  hasObsGivenUp(): boolean {
    return this.obsGaveUp;
  }

  /**
   * Start OBS's crash budget over. OBS runs on demand (see obs-demand.ts), so a new period of need,
   * after it was stopped for being unneeded, is a fresh start rather than more of an old crash loop.
   */
  resetObsBudget(): void {
    this.obsRestartAttempts = 0;
    this.obsGaveUp = false;
    this.lastObsRelaunchAt = Number.NEGATIVE_INFINITY;
  }

  /** OBS crashed (exited without a stop()). Bounded-relaunch it. */
  handleObsCrash(info: ChildExit): void {
    if (this.deps.isShuttingDown()) return;
    this.deps.log(`[obs] crash detected (${formatExit(info)})`);
    const t = this.now();
    if (t - this.lastObsRelaunchAt >= OBS_STABLE_MS) {
      this.obsRestartAttempts = 0; // it ran fine since the last relaunch: not a crash loop
    }
    if (this.obsRestartAttempts >= this.maxRestarts) {
      this.deps.log(
        '[obs] exceeded restart attempts; leaving OBS down (recorder will show not ready)',
      );
      this.obsGaveUp = true;
      return;
    }
    this.obsRestartAttempts += 1;
    this.lastObsRelaunchAt = t;
    this.deps.log(`[obs] relaunching (attempt ${this.obsRestartAttempts}/${this.maxRestarts})`);
    if (this.obsLaunching) {
      // The crashed OBS is the in-flight launch's own child (it died before start() settled). launchObs
      // would drop this relaunch as a duplicate, and that in-flight start() is about to reject
      // (superseded) — so without queuing, the budget is charged but nothing retries and OBS stays down
      // for the whole session. Queue it; the in-flight launchObs drains it once its startObs() settles.
      this.obsRelaunchPending = true;
      return;
    }
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
      // during its health wait). Don't run a second overlapping startCore() on the same core — but don't
      // silently drop it either: queue it so the in-flight restart, once it settles, consumes another
      // attempt for it. Without this, a restart-then-immediately-die loop wastes the maxRestarts budget
      // (the re-crash is dropped, and the failed restart leaves no live core to re-trigger this handler),
      // collapsing maxRestarts=2 to one effective attempt.
      this.deps.log('[core] crash during an in-flight restart; a restart is already running');
      this.corePendingRecrash = true;
      return;
    }
    this.coreRestarting = true;
    try {
      this.deps.log(`[core] crash detected (${formatExit(info)})`);
      // Best-effort: tearing down the orphaned OBS must never abort the core restart. A flaky
      // stopObs() rejection here would otherwise leave recording dead silently on a tray-hidden app.
      await this.deps.stopObs().catch((err) => {
        this.deps.log(
          `[obs] stop during core crash failed: ${err instanceof Error ? err.message : String(err)}`,
        );
      });
      // Restart loop: a crash that arrives WHILE startCore() is in flight is captured in
      // corePendingRecrash and re-loops here, so the remaining restart budget is actually consumed
      // rather than wasted on a single discarded attempt.
      do {
        this.corePendingRecrash = false;
        // Re-check the shutdown flag at the TOP of every iteration and after each await below. The
        // entry guard sampled it once, but a real quit can land at any await here (stopObs, startCore,
        // the reaps). Once shutdown is underway, bail SILENTLY: no notifyDown (a "recorder stopped"
        // tray balloon during a normal quit is spurious) and no startCore (respawning a JVM the quit
        // is trying to reap). The finally clears the crash-episode flags on the way out.
        if (this.deps.isShuttingDown()) return;
        if (this.coreRestartAttempts >= this.maxRestarts) {
          this.deps.notifyDown(
            'The recorder core stopped and could not be restarted. Restart the app to resume recording.',
          );
          return;
        }
        this.coreRestartAttempts += 1;
        this.deps.log(
          `[core] restarting (attempt ${this.coreRestartAttempts}/${this.maxRestarts})`,
        );
        try {
          await this.deps.startCore();
          // A quit can begin during startCore()'s health wait; bail before relaunching OBS or reaping
          // onto a core the shutdown is already tearing down.
          if (this.deps.isShuttingDown()) return;
          if (this.corePendingRecrash) {
            // A crash landed while this restart's health wait was in flight, yet startCore() still
            // resolved — the just-restarted core answered a health probe and then died in the same
            // window. Don't trust the resolve: relaunching OBS onto a dead core (and resetting the
            // budget) would silently drop that death. Reap and re-loop to spend another attempt.
            this.deps.log('[core] restarted core died during its health wait; retrying');
            await this.deps.stopCore().catch(() => {});
            if (this.deps.isShuttingDown()) return; // a quit landed during the reap
            continue;
          }
          this.deps.log('[core] restarted; relaunching OBS');
          this.coreRestartAttempts = 0; // a recovered core gets its restart budget back (over a long tray session)
          this.resetObsBudget(); // a fresh core epoch gets a fresh OBS restart budget
          void this.launchObs();
          return; // success: leave the loop and the handler
        } catch (err) {
          this.deps.log(
            `[core] restart failed: ${err instanceof Error ? err.message : String(err)}`,
          );
          // startCore() does NOT kill its child on a health-timeout, so a restarted-but-unhealthy JVM
          // would linger holding the loopback ports. Reap it before retrying or giving up.
          await this.deps.stopCore().catch(() => {});
          // A quit landing during the failed-restart reap must not fall through to the notifyDown below
          // (again, a spurious "recorder stopped" balloon during a normal quit).
          if (this.deps.isShuttingDown()) return;
          // If a re-crash queued during this attempt AND budget remains, the while-condition re-loops to
          // spend it; otherwise fall through to the user-facing notice below.
        }
      } while (this.corePendingRecrash);
      this.deps.notifyDown('The recorder core stopped. Restart the app to resume recording.');
    } finally {
      this.coreRestarting = false;
      this.corePendingRecrash = false; // bound the flag's lifetime to this one crash episode
    }
  }
}
