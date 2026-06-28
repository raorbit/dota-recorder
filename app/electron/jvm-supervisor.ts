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
import {
  BRIDGE_TOKEN_ENV,
  BRIDGE_TOKEN_HEADER,
  HEALTH_URL,
  bundledJavawPath,
  obsDir,
  obsSourceDir,
  obsVersion,
  resolveCoreJar,
} from './paths';

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
  private readonly onLog: (line: string) => void;
  private readonly onUnexpectedExit: ((info: ExitInfo) => void) | undefined;

  constructor(opts: SupervisorOptions = {}) {
    this.healthTimeoutMs = opts.healthTimeoutMs ?? 30_000;
    this.bridgeToken = opts.bridgeToken;
    this.onLog = opts.onLog ?? ((line) => console.log(`[core] ${line}`));
    this.onUnexpectedExit = opts.onUnexpectedExit;
  }

  /** Spawn the JVM core and resolve once GET /health reports ok. */
  async start(): Promise<void> {
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
    const jvmArgs = [
      `-Dapp.obs.dir=${obsDir()}`,
      ...(source ? [`-Dapp.obs.source-dir=${source}`] : []),
      `-Dapp.obs.version=${obsVersion()}`,
      // Pass our pid so the core's parent-death watchdog can self-exit if Electron dies hard and the
      // Job Object reaper was unavailable (koffi failed to load) — freeing :3223/:3224 and the
      // websocket port for the next launch instead of orphaning the JVM.
      `-Dapp.parent-pid=${process.pid}`,
      '-jar',
      jar,
    ];

    this.child = spawn(javaw, jvmArgs, {
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
      // Hand the core its bridge token out-of-band (env, not argv) so it can enforce
      // the shared secret on the loopback API. Absent token -> core runs auth-disabled.
      env: this.bridgeToken
        ? { ...process.env, [BRIDGE_TOKEN_ENV]: this.bridgeToken }
        : process.env,
    });

    if (this.child.pid !== undefined) {
      // Tie the child's lifetime to this process: the OS reaps it on hard crash.
      assignJob.assign(this.child.pid);
    }

    this.child.stdout?.on('data', (buf: Buffer) => this.emitLines(buf));
    this.child.stderr?.on('data', (buf: Buffer) => this.emitLines(buf));
    this.child.on('exit', (code, signal) => {
      // Clear the handle FIRST so onUnexpectedExit can safely restart via start().
      this.child = null;
      if (!this.stopping) {
        this.onLog(`core exited unexpectedly (code=${code ?? 'null'} signal=${signal ?? 'null'})`);
        this.onUnexpectedExit?.({ code, signal });
      }
    });

    await this.waitForHealth();
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
        spawn('taskkill', ['/PID', String(pid), '/T', '/F'], { windowsHide: true });
      } catch {
        child.kill('SIGKILL');
      }
    } else {
      child.kill('SIGKILL');
    }
    this.child = null;
  }

  private emitLines(buf: Buffer): void {
    const text = buf.toString('utf8');
    for (const line of text.split(/\r?\n/)) {
      if (line.length === 0) continue;
      // Secret hygiene: the bridge token lives in the core's env, and if the core ever echoes its
      // environment (a debug bean, a verbose stack trace) it would otherwise land in electron.log in
      // plaintext. Logs are durable and often shared in bug reports, so scrub it before it is written.
      const safe = this.bridgeToken ? line.split(this.bridgeToken).join('***') : line;
      this.onLog(safe);
    }
  }

  private async waitForHealth(): Promise<void> {
    const deadline = Date.now() + this.healthTimeoutMs;
    let delay = 200;
    let lastError: unknown = null;

    while (Date.now() < deadline) {
      if (this.child === null && this.stopping) {
        throw new Error('Supervisor stopped before core became healthy.');
      }
      try {
        const res = await fetch(HEALTH_URL, {
          signal: AbortSignal.timeout(1_500),
          ...(this.bridgeToken ? { headers: { [BRIDGE_TOKEN_HEADER]: this.bridgeToken } } : {}),
        });
        if (res.ok) {
          const body = (await res.json()) as { status?: string };
          if (body.status === 'ok') return;
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
