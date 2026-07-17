// Supervises the bundled Spring Boot JVM core. Electron is the SOLE supervisor:
// it spawns the core, waits for /health, and kills the whole JVM tree on quit.
//
// Plan / process-supervision notes:
//  - readiness is detected by POLLING GET /health (Spring cold start is variable,
//    ~1-4s), never a fixed sleep.
//  - on quit: attempt graceful shutdown, then escalate to a hard tree-kill
//    (Windows Job Object kill-on-close if koffi is present, else taskkill /T /F).
//
// TODO(plan Step 1+): POST /shutdown (graceful), stale-port detection, and a
// loud status-card error on crash are later steps; this is the Step 0 skeleton.
import { spawn, type ChildProcess } from 'node:child_process';
import * as assignJob from './job-object';
import { scrubChunk, scrubSecrets } from './scrub';
import {
  BRIDGE_TOKEN_ENV,
  BRIDGE_TOKEN_HEADER,
  HEALTH_URL,
  bundledJavawPath,
  ffmpegPath,
  obsDir,
  obsSourceDir,
  obsVersion,
  resolveCoreJar,
} from './paths';

// Env var the core (FfmpegLocator) reads as a fallback for the bundled ffmpeg path;
// must stay identical to FfmpegLocator.FFMPEG_PATH_ENV in the core.
const FFMPEG_PATH_ENV = 'DOTAREC_FFMPEG_PATH';

export interface SupervisorOptions {
  /** Total time to wait for /health before giving up. */
  readonly healthTimeoutMs?: number;
  /** Per-launch bridge token: injected into the core's env and sent on the health poll. */
  readonly bridgeToken?: string;
  /** Called with each line of core stdout/stderr (for the log file later). */
  readonly onLog?: (line: string) => void;
  /**
   * Called when the core exits WITHOUT a stop() request — i.e. it crashed. The supervisor has
   * already cleared its child handle by the time this fires, so calling start() again from inside
   * the callback (a restart) is safe.
   */
  readonly onUnexpectedExit?: (info: ExitInfo) => void;
}

export interface ExitInfo {
  readonly code: number | null;
  readonly signal: NodeJS.Signals | null;
}

export class JvmSupervisor {
  private child: ChildProcess | null = null;
  private stopping = false;
  private readonly healthTimeoutMs: number;
  private readonly bridgeToken: string | undefined;
  // Secrets masked out of every core log line by emitLines. Seeded with the bridge token (known at
  // construction); the OBS websocket password is core-owned and only knowable after boot, so it's
  // appended later via addScrubSecret once main.ts fetches it from /obs/launch-args.
  private readonly logSecrets: Array<string | undefined>;
  // Residual partial log line held back between 'data' events, kept SEPARATE per stream. A child's
  // output arrives in arbitrary slices, so a secret (or any line) can straddle a chunk boundary;
  // buffering the trailing incomplete segment and prepending it to the next chunk of the SAME stream
  // lets emitLines scrub whole lines only. stdout and stderr must NOT share one buffer: an interleaved
  // partial line from one stream would then be spliced onto the other's next chunk — garbling the log
  // and defeating the scrub (a stderr line landing between a secret's two stdout halves would rejoin
  // the wrong pieces and leak it). Both are flushed on 'exit' so a final unterminated line still logs.
  private stdoutLineBuffer = '';
  private stderrLineBuffer = '';
  private readonly onLog: (line: string) => void;
  private readonly onUnexpectedExit: ((info: ExitInfo) => void) | undefined;

  constructor(opts: SupervisorOptions = {}) {
    this.healthTimeoutMs = opts.healthTimeoutMs ?? 30_000;
    this.bridgeToken = opts.bridgeToken;
    this.logSecrets = [opts.bridgeToken];
    this.onLog = opts.onLog ?? ((line) => console.log(`[core] ${line}`));
    this.onUnexpectedExit = opts.onUnexpectedExit;
  }

  /**
   * Register a secret to mask from all subsequent core log lines. Used for values the core mints at
   * runtime (the OBS websocket password), which aren't known when the supervisor is constructed.
   * Idempotent: re-adding a secret already in the list is a no-op.
   */
  addScrubSecret(secret: string): void {
    if (!secret || this.logSecrets.includes(secret)) return;
    this.logSecrets.push(secret);
  }

  /** Spawn the JVM core and resolve once GET /health reports ok. */
  async start(): Promise<void> {
    // A prior stop() latches `stopping` true — including the failed-restart reap in
    // SupervisionController.handleCoreCrash, which stop()s and then start()s this SAME instance.
    // Without resetting it here, every later crash of the new child would read as a requested stop
    // and never reach onUnexpectedExit — permanently muting crash recovery (recording would just
    // silently stop on a tray-hidden app).
    this.stopping = false;
    const jar = resolveCoreJar();
    if (!jar) {
      throw new Error(
        'Could not locate core.jar. Build the core (core/build/libs) or set DOTAREC_CORE_JAR.',
      );
    }

    // Packaged: bundled javaw.exe. Dev: rely on javaw on PATH.
    const javaw = bundledJavawPath() ?? 'javaw';

    // Thread OBS locations into the JVM as system properties so the core's
    // ObsConfigWriter knows where to materialize/launch OBS from. source-dir is
    // omitted in dev when no bundled OBS is present (core skips the first-run copy).
    const source = obsSourceDir();
    // Bundled ffmpeg path, threaded into the core as a system property (preferred) AND env
    // (belt-and-suspenders) so the core's FfmpegLocator resolves it without ffmpeg on PATH.
    // Omitted in dev when no bundled ffmpeg is present (the core falls back to "ffmpeg" on PATH).
    const ffmpeg = ffmpegPath();
    const jvmArgs = [
      // Cap the heap: without this, JVM ergonomics size it from HOST RAM (max 1/4, initial 1/64 —
      // 16 GB / 1 GB on a 64 GB machine), so the core commits ~1 GB of heap it never touches and
      // shows an alarming Commit size in Task Manager. Real heap need is well under 100 MB.
      '-Xms64m',
      '-Xmx256m',
      `-Dapp.obs.dir=${obsDir()}`,
      ...(source ? [`-Dapp.obs.source-dir=${source}`] : []),
      `-Dapp.obs.version=${obsVersion()}`,
      ...(ffmpeg ? [`-Dapp.ffmpeg.path=${ffmpeg}`] : []),
      // Pass our pid so the core's parent-death watchdog can self-exit if Electron dies hard and the
      // Job Object reaper was unavailable (koffi failed to load) — freeing :3223/:3224 and the
      // websocket port for the next launch instead of orphaning the JVM.
      `-Dapp.parent-pid=${process.pid}`,
      '-jar',
      jar,
    ];

    // Hand the core its bridge token out-of-band (env, not argv) so it can enforce the shared
    // secret on the loopback API (absent token -> core runs auth-disabled), and the bundled ffmpeg
    // path as a belt-and-suspenders fallback to the -Dapp.ffmpeg.path system property above.
    const env: NodeJS.ProcessEnv = { ...process.env };
    if (this.bridgeToken) env[BRIDGE_TOKEN_ENV] = this.bridgeToken;
    if (ffmpeg) env[FFMPEG_PATH_ENV] = ffmpeg;

    const child = spawn(javaw, jvmArgs, {
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
      env,
    });
    this.child = child;

    if (child.pid !== undefined) {
      // Tie the child's lifetime to this process: the OS reaps it on hard crash.
      assignJob.assign(child.pid);
    }

    child.stdout?.on('data', (buf: Buffer) => this.emitLines(buf, 'stdout'));
    child.stderr?.on('data', (buf: Buffer) => this.emitLines(buf, 'stderr'));
    child.on('exit', (code, signal) => {
      // Only clear the handle if THIS child is still current — a newer start() (e.g. a crash-triggered
      // restart) may already have replaced it. Clearing it FIRST lets onUnexpectedExit restart cleanly.
      const isCurrent = this.child === child;
      if (isCurrent) {
        // Flush any buffered partial final line (a last line the core wrote without a trailing newline),
        // scrubbed like any other. Gated on isCurrent so a superseded child's late exit can't flush —
        // and clear — the live child's in-progress buffer.
        this.flushLogBuffer();
        this.child = null;
      }
      // Gate on isCurrent too: a superseded child's late exit (stop()'s taskkill landing after the
      // next start() already reset `stopping`) is not a crash of the NEW core — reporting it would
      // trigger a restart cycle that kills a healthy core.
      if (isCurrent && !this.stopping) {
        this.onLog(`core exited unexpectedly (code=${code ?? 'null'} signal=${signal ?? 'null'})`);
        this.onUnexpectedExit?.({ code, signal });
      }
    });

    // A failed spawn (missing/unlaunchable javaw, ENOENT/EACCES) emits 'error' and never 'exit'. With
    // no listener Node re-emits it as an uncaughtException that takes down the Electron main process,
    // bypassing the supervisor's controlled failure path. Keep a PERSISTENT listener so an 'error' is
    // never unhandled: during startup it rejects start() (the caller then runs notifyDown / bounded
    // restart); after startup (rare) it is only logged — the handle is left intact so a later stop()
    // still reaps the process, and we don't spuriously restart since 'error' doesn't imply it exited.
    let launchSettled = false;
    let rejectLaunch: ((err: Error) => void) | undefined;
    const launchFailed = new Promise<never>((_, reject) => {
      rejectLaunch = reject;
    });
    child.on('error', (err) => {
      const e = err instanceof Error ? err : new Error(String(err));
      if (launchSettled) {
        this.onLog(`core errored after startup: ${e.message}`);
        return;
      }
      if (this.child === child) this.child = null;
      this.onLog(`core failed to launch: ${e.message}`);
      rejectLaunch?.(e);
    });

    try {
      await Promise.race([this.waitForHealth(child), launchFailed]);
    } finally {
      launchSettled = true; // past this point an 'error' is post-startup, not a launch failure
    }
  }

  /** Graceful-then-forceful shutdown of the JVM tree. */
  async stop(): Promise<void> {
    this.stopping = true;
    const child = this.child;
    if (!child || child.pid === undefined) {
      this.child = null;
      return;
    }
    const pid = child.pid;

    // Step 1: graceful. SIGTERM lets Spring run shutdown hooks where supported.
    // TODO(plan Step 1+): prefer POST /shutdown before signalling.
    try {
      child.kill('SIGTERM');
    } catch {
      /* ignore - escalate below. */
    }

    const exitedCleanly = await this.waitForExit(child, 4_000);
    if (exitedCleanly) {
      this.child = null;
      return;
    }

    // Step 2: hard tree-kill. The Job Object (if present) already covers a hard
    // parent crash; taskkill /T /F is the explicit fallback on graceful quit.
    if (process.platform === 'win32') {
      try {
        const killer = spawn('taskkill', ['/PID', String(pid), '/T', '/F'], { windowsHide: true });
        // spawn reports a LAUNCH failure (taskkill missing / EPERM) asynchronously via 'error'; with no
        // listener Node re-emits it as an uncaught exception and kills the Electron main process, so the
        // catch's SIGKILL fallback never runs. Handle it here to fall back instead of crashing.
        killer.on('error', () => {
          try {
            child.kill('SIGKILL');
          } catch {
            /* nothing left to escalate to. */
          }
        });
      } catch {
        child.kill('SIGKILL');
      }
    } else {
      child.kill('SIGKILL');
    }
    this.child = null;
  }

  private emitLines(buf: Buffer, stream: 'stdout' | 'stderr'): void {
    // The bridge token and the core-minted OBS websocket password both live in / flow through the
    // core; if it ever echoes them (a debug bean, a verbose stack trace, a settings dump) they would
    // otherwise land in electron.log in plaintext. scrubChunk buffers a partial trailing line across
    // chunks (per stream) so a secret split over two 'data' events is still rejoined and redacted.
    const prev = stream === 'stdout' ? this.stdoutLineBuffer : this.stderrLineBuffer;
    const { lines, leftover } = scrubChunk(prev, buf.toString('utf8'), this.logSecrets);
    if (stream === 'stdout') this.stdoutLineBuffer = leftover;
    else this.stderrLineBuffer = leftover;
    for (const line of lines) this.onLog(line);
  }

  // Flush both buffered partial final lines (a last line the core wrote without a trailing newline
  // before exiting), scrubbed like any other so a trailing secret can't slip through unredacted.
  private flushLogBuffer(): void {
    const out = this.stdoutLineBuffer;
    const err = this.stderrLineBuffer;
    this.stdoutLineBuffer = '';
    this.stderrLineBuffer = '';
    if (out.length > 0) this.onLog(scrubSecrets(out, this.logSecrets));
    if (err.length > 0) this.onLog(scrubSecrets(err, this.logSecrets));
  }

  private async waitForHealth(child: ChildProcess): Promise<void> {
    const deadline = Date.now() + this.healthTimeoutMs;
    let delay = 200;
    let lastError: unknown = null;

    while (Date.now() < deadline) {
      if (this.child !== child) {
        // This generation was superseded (a newer start() replaced the child) or the child exited
        // (a crash/stop nulled it). Abort promptly instead of polling a dead or foreign core for the
        // full timeout — that is what let a crash during restart spawn overlapping waitForHealth loops.
        throw new Error('Supervisor superseded before core became healthy.');
      }
      try {
        const res = await fetch(HEALTH_URL, {
          signal: AbortSignal.timeout(1_500),
          ...(this.bridgeToken ? { headers: { [BRIDGE_TOKEN_HEADER]: this.bridgeToken } } : {}),
        });
        if (res.ok) {
          // /health answers `ok` as soon as the web server binds — which is BEFORE the migration
          // runner finishes (it runs as an ApplicationRunner, after the port is open). Gating only on
          // status would let the renderer mount and fire its initial queries straight into a
          // half-migrated DB (those requests fail and never retry). Require dbReady too, so the poll
          // waits the extra few ms until the schema exists. createWindow() runs only after start()
          // resolves, so this closes the race at the root.
          const body = (await res.json()) as { status?: string; dbReady?: boolean };
          if (body.status === 'ok' && body.dbReady === true) return;
        }
      } catch (err) {
        lastError = err;
      }
      await sleep(delay);
      delay = Math.min(delay * 1.5, 2_000);
    }

    throw new Error(
      `Core did not become healthy within ${this.healthTimeoutMs}ms` +
        (lastError instanceof Error ? `: ${lastError.message}` : ''),
    );
  }

  private waitForExit(child: ChildProcess, timeoutMs: number): Promise<boolean> {
    return new Promise<boolean>((resolve) => {
      let settled = false;
      const done = (value: boolean): void => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve(value);
      };
      const timer = setTimeout(() => done(false), timeoutMs);
      child.once('exit', () => done(true));
    });
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
