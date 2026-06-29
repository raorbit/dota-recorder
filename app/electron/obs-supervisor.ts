// Supervises the bundled OBS Studio process. Mirrors JvmSupervisor: Electron is
// the SOLE supervisor — it spawns obs64.exe from the writable OBS dir, waits for the
// core to report OBS connected via GET /status (the core auto-connects to OBS over
// the websocket on a scheduled retry loop), and kills the whole OBS tree on quit.
//
// Plan / process-supervision notes:
//  - OBS startup is variable (esp. first-run materialize/copy), so readiness is
//    detected by POLLING GET /status for obs.connected, never a fixed sleep. (/health
//    is hardcoded ok and would not actually gate on OBS being up.)
//  - on quit: attempt graceful shutdown (SIGTERM), then escalate to a hard
//    tree-kill (taskkill /T /F on Windows, SIGKILL elsewhere).
//  - the OBS pid is assigned to the same kill-on-close Job Object as the core so
//    a hard Electron crash reaps OBS too (it would otherwise hold the websocket
//    port and a capture lock, blocking the next launch).
import { execFile, spawn, type ChildProcess } from 'node:child_process';
import * as fs from 'node:fs';
import * as path from 'node:path';
import * as assignJob from './job-object';
import { BRIDGE_BASE, BRIDGE_TOKEN_HEADER } from './paths';

export interface ObsSupervisorOptions {
  /** Writable OBS dir (%LOCALAPPDATA%/dota-recorder/obs) — obs64.exe lives under bin/64bit. */
  readonly obsDir: string;
  /** obs-websocket port the core handed us from /obs/launch-args. */
  readonly port: number;
  /** obs-websocket password the core handed us from /obs/launch-args. */
  readonly password: string;
  /** OBS scene-collection / profile / scene names — owned by the core (it creates them), passed
   * through /obs/launch-args so the launch args don't re-hardcode strings that could drift. */
  readonly collection: string;
  readonly profile: string;
  readonly scene: string;
  /** Total time to wait for the core to report OBS healthy before giving up. */
  readonly healthTimeoutMs?: number;
  /** Per-launch bridge token, sent on the /status poll the readiness check makes. */
  readonly bridgeToken?: string;
  /** Called with each line of OBS stdout/stderr (for the log file). */
  readonly onLog?: (line: string) => void;
  /**
   * Called when OBS exits WITHOUT a stop() request (a crash). The supervisor has already cleared its
   * child handle, so the caller can relaunch a fresh ObsSupervisor in response.
   */
  readonly onUnexpectedExit?: (info: ObsExitInfo) => void;
}

export interface ObsExitInfo {
  readonly code: number | null;
  readonly signal: NodeJS.Signals | null;
}

export class ObsSupervisor {
  private child: ChildProcess | null = null;
  private stopping = false;
  private readonly obsDir: string;
  private readonly port: number;
  private readonly password: string;
  private readonly collection: string;
  private readonly profile: string;
  private readonly scene: string;
  private readonly healthTimeoutMs: number;
  private readonly bridgeToken: string | undefined;
  private readonly onLog: (line: string) => void;
  private readonly onUnexpectedExit: ((info: ObsExitInfo) => void) | undefined;

  constructor(opts: ObsSupervisorOptions) {
    this.obsDir = opts.obsDir;
    this.port = opts.port;
    this.password = opts.password;
    this.collection = opts.collection;
    this.profile = opts.profile;
    this.scene = opts.scene;
    this.healthTimeoutMs = opts.healthTimeoutMs ?? 30_000;
    this.bridgeToken = opts.bridgeToken;
    this.onLog = opts.onLog ?? ((line) => console.log(`[obs] ${line}`));
    this.onUnexpectedExit = opts.onUnexpectedExit;
  }

  /** Spawn obs64.exe with the exact portable arg list and resolve once OBS is healthy. */
  async start(): Promise<void> {
    const obs64Path = path.join(this.obsDir, 'bin', '64bit', 'obs64.exe');
    if (!fs.existsSync(obs64Path)) {
      throw new Error(`OBS executable not found: ${obs64Path}`);
    }

    // Exact portable launch args (all required). --portable + --multi keep OBS
    // self-contained and allow a second instance; the websocket flags must match
    // the port/password the core generated, or the core can never connect.
    const args = [
      '--portable',
      '--multi',
      '--minimize-to-tray',
      '--disable-updater',
      '--disable-missing-files-check',
      '--collection',
      this.collection,
      '--profile',
      this.profile,
      '--scene',
      this.scene,
      '--websocket_port',
      String(this.port),
      '--websocket_password',
      this.password,
      '--websocket_ipv4_only',
    ];

    // Scope the managed websocket port to loopback before OBS binds it (issue #14).
    await this.scopePortToLoopback();

    // OBS resolves its `data/` tree relative to the working directory (it probes
    // `data/...` and `../../data/...`), so cwd MUST be bin/64bit where obs64.exe lives —
    // launching from the install root makes every data asset (theme included) fail to load,
    // OBS aborts with a fatal "Failed to load theme" before binding the websocket. Portable
    // config/logs still land under the root: portable mode resolves those via the exe path
    // (`../../config`), not cwd, so they stay alongside the binary regardless.
    const child = spawn(obs64Path, args, {
      cwd: path.join(this.obsDir, 'bin', '64bit'),
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    this.child = child;

    if (child.pid !== undefined) {
      // Tie OBS's lifetime to this process: the OS reaps it on hard crash.
      assignJob.assign(child.pid);
    }

    child.stdout?.on('data', (buf: Buffer) => this.emitLines(buf));
    child.stderr?.on('data', (buf: Buffer) => this.emitLines(buf));
    child.on('exit', (code, signal) => {
      // Only clear the handle if THIS child is still current (a newer start() may have replaced it).
      if (this.child === child) {
        this.child = null;
      }
      if (!this.stopping) {
        this.onLog(`OBS exited unexpectedly (code=${code ?? 'null'} signal=${signal ?? 'null'})`);
        this.onUnexpectedExit?.({ code, signal });
      }
    });

    // A failed spawn emits 'error' and never 'exit'; with no listener Node re-emits it as an
    // uncaughtException that kills the Electron main process. The existsSync precheck above catches a
    // missing obs64.exe, but EACCES / a corrupt binary / a TOCTOU delete still reach spawn — race the
    // error against the readiness wait so start() rejects with the real cause instead of crashing.
    const launchFailed = new Promise<never>((_, reject) => {
      child.once('error', (err) => {
        if (this.child === child) this.child = null;
        const e = err instanceof Error ? err : new Error(String(err));
        this.onLog(`OBS failed to launch: ${e.message}`);
        reject(e);
      });
    });

    await Promise.race([this.waitForObs(child), launchFailed]);
  }

  /** Graceful-then-forceful shutdown of the OBS tree. */
  async stop(): Promise<void> {
    this.stopping = true;
    const child = this.child;
    if (!child || child.pid === undefined) {
      this.child = null;
      return;
    }
    const pid = child.pid;

    // Step 1: graceful. SIGTERM lets OBS flush its config and stop captures cleanly.
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

  /**
   * Best-effort: block inbound LAN access to the managed obs-websocket port, scoping it to
   * loopback. obs-websocket binds all interfaces (0.0.0.0) and exposes no bind-address option, so
   * a Windows Firewall block rule on the port is the only scoping (issue #14). Loopback traffic
   * (127.0.0.1 — our own client) bypasses the firewall entirely, so the block hits the LAN only.
   *
   * Editing firewall rules needs admin, so an unelevated (per-user) launch logs a warning and runs
   * with the port exposed-but-authed; the reliable, elevated form is an installer-time rule.
   */
  private async scopePortToLoopback(): Promise<void> {
    if (process.platform !== 'win32') return;
    const name = `DotaRecorder OBS WebSocket loopback ${this.port}`;
    const port = String(this.port);
    // Idempotent: drop any prior rule of this name (ignoring "not found"), then add a fresh block.
    await this.runNetsh([
      'advfirewall', 'firewall', 'delete', 'rule', `name=${name}`, 'protocol=TCP', `localport=${port}`,
    ]);
    const added = await this.runNetsh([
      'advfirewall', 'firewall', 'add', 'rule',
      `name=${name}`, 'dir=in', 'action=block', 'protocol=TCP', `localport=${port}`,
    ]);
    this.onLog(
      added
        ? `firewall: scoped obs-websocket :${port} to loopback`
        : `firewall: could not scope :${port} to loopback (needs admin); port is auth-protected but LAN-reachable`,
    );
  }

  /** Runs a netsh command; resolves true on exit 0, false otherwise (never throws). */
  private runNetsh(args: string[]): Promise<boolean> {
    return new Promise((resolve) => {
      execFile('netsh', args, { windowsHide: true, timeout: 10_000 }, (err) => resolve(!err));
    });
  }

  private emitLines(buf: Buffer): void {
    const text = buf.toString('utf8');
    for (const line of text.split(/\r?\n/)) {
      if (line.length > 0) this.onLog(line);
    }
  }

  private async waitForObs(child: ChildProcess): Promise<void> {
    const deadline = Date.now() + this.healthTimeoutMs;
    let delay = 200;
    let lastError: unknown = null;

    while (Date.now() < deadline) {
      if (this.child !== child) {
        // Superseded by a newer start(), or the child exited (crash/stop nulled it): stop polling.
        throw new Error('Supervisor superseded before OBS became healthy.');
      }
      try {
        // The core auto-connects to OBS on its retry loop; /status reflects that via
        // obs.connected (unlike /health, which is hardcoded ok and never gates on OBS).
        const res = await fetch(`${BRIDGE_BASE}/status`, {
          signal: AbortSignal.timeout(1_500),
          ...(this.bridgeToken ? { headers: { [BRIDGE_TOKEN_HEADER]: this.bridgeToken } } : {}),
        });
        if (res.ok) {
          const body = (await res.json()) as { obs?: { connected?: boolean } };
          if (body.obs?.connected === true) return;
        }
      } catch (err) {
        lastError = err;
      }
      await sleep(delay);
      delay = Math.min(delay * 1.5, 2_000);
    }

    throw new Error(
      `OBS did not connect within ${this.healthTimeoutMs}ms` +
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
