// On-demand OBS policy, kept free of Electron so it can be unit-tested. main.ts polls the core's
// GET /status and feeds each reading to tick(): OBS is launched while the core reports it wanted
// (Dota running, a match arming or recording, the settings preview open — see the core's ObsDemand)
// and stopped once it has gone unwanted for OBS_IDLE_STOP_MS.
//
// Before this, OBS ran for the app's whole lifetime. Even idle it renders its scene and holds audio
// captures open, and a second always-on OBS next to it (deadlock-recorder) was enough to make browser
// video stutter.

/** How often main.ts polls the core for whether OBS is wanted. */
export const OBS_DEMAND_POLL_MS = 3_000;

/**
 * How long OBS must have gone unwanted before it is stopped. Long enough that a quick Dota restart or
 * re-queue doesn't bounce OBS (a cold start costs several seconds plus encoder warm-up).
 */
export const OBS_IDLE_STOP_MS = 90_000;

/**
 * Minimum spacing between launches this controller starts. A crash is relaunched (and budgeted) by
 * SupervisionController, but a launch that fails outright (a missing obs64.exe, a spawn error) is not
 * a crash, and without this the 3s poll would retry it forever.
 */
export const OBS_LAUNCH_RETRY_MS = 30_000;

/** What one /status reading says about OBS. */
export interface ObsDemandReading {
  /** The core wants OBS running (obs.wanted). */
  readonly wanted: boolean;
  /** A match is arming/recording/stopping or OBS has an active output: never stop OBS under it. */
  readonly busy: boolean;
}

export interface ObsDemandDeps {
  /** One reading from GET /status, or null when the core didn't answer usably. */
  readStatus(): Promise<ObsDemandReading | null>;
  /** An OBS process is running under our supervisor. */
  isObsRunning(): boolean;
  /** A launch (or crash relaunch) is in flight. */
  isObsLaunching(): boolean;
  /** False once crash relaunches have used up their budget. */
  canLaunch(): boolean;
  /** Start a launch; it runs in the background. */
  launchObs(): void;
  /** Stop OBS on purpose (not a crash, so nothing relaunches it). */
  stopObs(): Promise<void>;
  /** A new period of need starts with a fresh crash-relaunch budget. */
  resetBudget(): void;
  isShuttingDown(): boolean;
  log(message: string): void;
  /** Monotonic milliseconds. */
  now(): number;
}

export class ObsDemandController {
  private lastNeededAt: number;
  private lastLaunchAt = Number.NEGATIVE_INFINITY;
  private ticking = false;
  private loggedGaveUp = false;

  constructor(
    private readonly deps: ObsDemandDeps,
    private readonly idleStopMs = OBS_IDLE_STOP_MS,
    private readonly launchRetryMs = OBS_LAUNCH_RETRY_MS,
  ) {
    this.lastNeededAt = deps.now();
  }

  /** One poll. Overlapping calls are dropped, so a slow stop can't be re-entered. */
  async tick(): Promise<void> {
    if (this.ticking || this.deps.isShuttingDown()) return;
    this.ticking = true;
    try {
      const reading = await this.deps.readStatus();
      // An unreachable or confused core says nothing about OBS: leave it as it is.
      if (reading === null || this.deps.isShuttingDown()) return;
      const t = this.deps.now();
      if (reading.wanted || reading.busy) {
        this.lastNeededAt = t;
        this.ensureRunning(t);
        return;
      }
      if (t - this.lastNeededAt < this.idleStopMs) return;
      // The need is over: the next one gets a fresh crash budget and a fresh "gave up" notice.
      this.deps.resetBudget();
      this.loggedGaveUp = false;
      if (this.deps.isObsRunning() && !this.deps.isObsLaunching()) {
        this.deps.log(`[obs] not needed for ${Math.round(this.idleStopMs / 1000)}s; stopping OBS`);
        await this.deps.stopObs();
      }
    } catch (err) {
      this.deps.log(`[obs] demand check failed: ${err instanceof Error ? err.message : String(err)}`);
    } finally {
      this.ticking = false;
    }
  }

  private ensureRunning(t: number): void {
    if (this.deps.isObsRunning() || this.deps.isObsLaunching()) return;
    if (!this.deps.canLaunch()) {
      if (!this.loggedGaveUp) {
        this.loggedGaveUp = true;
        this.deps.log('[obs] needed, but its relaunch budget is spent; leaving it down');
      }
      return;
    }
    if (t - this.lastLaunchAt < this.launchRetryMs) return;
    this.lastLaunchAt = t;
    this.deps.log('[obs] needed; launching OBS');
    this.deps.launchObs();
  }
}
