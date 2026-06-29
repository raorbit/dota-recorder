import { EventEmitter } from 'node:events';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Hoisted so the (hoisted) vi.mock factory below can reference it.
const { spawnMock, ffmpegPathMock } = vi.hoisted(() => ({
  spawnMock: vi.fn(),
  // Controllable per-test: default "no bundled ffmpeg" (dev), override to a path to assert wiring.
  ffmpegPathMock: vi.fn(() => null as string | null),
}));

vi.mock('node:child_process', async (importOriginal) => {
  const actual = await importOriginal<typeof import('node:child_process')>();
  return { ...actual, spawn: (...args: unknown[]) => spawnMock(...args) };
});
vi.mock('./job-object', () => ({ assign: vi.fn() }));
vi.mock('./paths', () => ({
  BRIDGE_TOKEN_ENV: 'DOTAREC_BRIDGE_TOKEN',
  BRIDGE_TOKEN_HEADER: 'X-Dotarec-Token',
  HEALTH_URL: 'http://127.0.0.1:3224/health',
  bundledJavawPath: () => null,
  ffmpegPath: ffmpegPathMock,
  obsDir: () => 'C:/obs',
  obsSourceDir: () => undefined,
  obsVersion: () => '1',
  resolveCoreJar: () => 'C:/core.jar',
}));

import { JvmSupervisor } from './jvm-supervisor';

/** Minimal stand-in for a spawned ChildProcess the supervisor drives via events. */
class FakeChild extends EventEmitter {
  pid: number | undefined = 4321;
  stdout = new EventEmitter();
  stderr = new EventEmitter();
  kill = vi.fn();
}

let children: FakeChild[];
let healthOk: boolean;

beforeEach(() => {
  children = [];
  healthOk = true;
  ffmpegPathMock.mockReset();
  ffmpegPathMock.mockReturnValue(null);
  spawnMock.mockReset();
  spawnMock.mockImplementation(() => {
    const child = new FakeChild();
    children.push(child);
    return child;
  });
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: healthOk,
      // Readiness requires BOTH status=ok and dbReady=true (migrations finished). A healthy core
      // reports both; the unhealthy stub reports neither.
      json: async () => ({ status: healthOk ? 'ok' : 'starting', dbReady: healthOk }),
    })),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

const flush = (ms = 0): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

describe('JvmSupervisor', () => {
  it('resolves once /health reports ok', async () => {
    const sup = new JvmSupervisor({ onLog: () => {} });
    await expect(sup.start()).resolves.toBeUndefined();
    expect(spawnMock).toHaveBeenCalledTimes(1);
  });

  it('passes its own pid to the core so the parent-death watchdog can self-reap', async () => {
    const sup = new JvmSupervisor({ onLog: () => {} });
    await sup.start();
    const args = spawnMock.mock.calls[0][1] as string[];
    expect(args).toContain(`-Dapp.parent-pid=${process.pid}`);
  });

  it('threads the bundled ffmpeg path into the core (system property + env) when present', async () => {
    ffmpegPathMock.mockReturnValue('C:/ffmpeg/ffmpeg.exe');
    const sup = new JvmSupervisor({ onLog: () => {} });
    await sup.start();
    const args = spawnMock.mock.calls[0][1] as string[];
    expect(args).toContain('-Dapp.ffmpeg.path=C:/ffmpeg/ffmpeg.exe');
    const opts = spawnMock.mock.calls[0][2] as { env: NodeJS.ProcessEnv };
    expect(opts.env.DOTAREC_FFMPEG_PATH).toBe('C:/ffmpeg/ffmpeg.exe');
  });

  it('omits the ffmpeg property and env when no bundled ffmpeg is present', async () => {
    // default ffmpegPathMock -> null (dev with ffmpeg only on PATH)
    const sup = new JvmSupervisor({ onLog: () => {} });
    await sup.start();
    const args = spawnMock.mock.calls[0][1] as string[];
    expect(args.some((a) => a.startsWith('-Dapp.ffmpeg.path='))).toBe(false);
    const opts = spawnMock.mock.calls[0][2] as { env: NodeJS.ProcessEnv };
    expect(opts.env.DOTAREC_FFMPEG_PATH).toBeUndefined();
  });

  it('fires onUnexpectedExit when the core exits without a stop()', async () => {
    const onUnexpectedExit = vi.fn();
    const sup = new JvmSupervisor({ onLog: () => {}, onUnexpectedExit });
    await sup.start();

    children[0].emit('exit', 1, null);

    expect(onUnexpectedExit).toHaveBeenCalledTimes(1);
    expect(onUnexpectedExit).toHaveBeenCalledWith({ code: 1, signal: null });
  });

  it('does NOT fire onUnexpectedExit when stop() requested the exit', async () => {
    const onUnexpectedExit = vi.fn();
    const sup = new JvmSupervisor({ onLog: () => {}, onUnexpectedExit });
    await sup.start();

    const stopped = sup.stop();
    children[0].emit('exit', 0, 'SIGTERM'); // the exit stop() is waiting for
    await stopped;

    expect(onUnexpectedExit).not.toHaveBeenCalled();
    expect(children[0].kill).toHaveBeenCalledWith('SIGTERM');
  });

  it('aborts the health poll promptly when the child crashes during startup (generation guard)', async () => {
    healthOk = false; // /health never ok, so without the guard waitForHealth would poll to the deadline
    const sup = new JvmSupervisor({ onLog: () => {}, healthTimeoutMs: 5_000 });

    const starting = sup.start();
    await flush(30); // let the first poll begin
    children[0].emit('exit', 1, null); // exit handler nulls this.child -> the generation guard trips

    await expect(starting).rejects.toThrow(/superseded/i);
  });

  it('rejects if /health never becomes healthy within the timeout', async () => {
    healthOk = false;
    const sup = new JvmSupervisor({ onLog: () => {}, healthTimeoutMs: 250 });

    await expect(sup.start()).rejects.toThrow(/did not become healthy/i);
  });

  it('keeps waiting while migrations run ({status:ok, dbReady:false}) and does not resolve', async () => {
    // /health flips to ok the instant the web server binds, but the schema migration runs afterward.
    // Resolving on status alone would mount the renderer into a half-migrated DB; dbReady gates it.
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: true, json: async () => ({ status: 'ok', dbReady: false }) })),
    );
    const sup = new JvmSupervisor({ onLog: () => {}, healthTimeoutMs: 250 });

    await expect(sup.start()).rejects.toThrow(/did not become healthy/i);
  });

  it('resolves once dbReady flips true after migrations finish', async () => {
    let ready = false;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: true, json: async () => ({ status: 'ok', dbReady: ready }) })),
    );
    const sup = new JvmSupervisor({ onLog: () => {}, healthTimeoutMs: 5_000 });

    const starting = sup.start();
    await flush(50);
    ready = true; // migrations complete -> the next poll should resolve

    await expect(starting).resolves.toBeUndefined();
  });

  it('rejects start() with the real error (not crash) when the child fails to launch', async () => {
    // A missing/unlaunchable javaw emits 'error' and never 'exit'. With no error listener Node re-emits
    // it as an uncaughtException that kills the Electron main process; instead start() must reject with
    // the real ENOENT so the supervisor's controlled notifyDown / restart path runs.
    healthOk = false; // keep waitForHealth polling so the launch error wins the race
    const sup = new JvmSupervisor({ onLog: () => {}, healthTimeoutMs: 5_000 });

    const starting = sup.start();
    await flush(10);
    children[0].emit('error', Object.assign(new Error('spawn javaw ENOENT'), { code: 'ENOENT' }));

    await expect(starting).rejects.toThrow(/ENOENT/);
  });

  it('scrubs the bridge token from emitted log lines', async () => {
    const lines: string[] = [];
    const sup = new JvmSupervisor({ bridgeToken: 'secrettoken', onLog: (line) => lines.push(line) });
    await sup.start();

    children[0].stdout.emit('data', Buffer.from('before secrettoken after\n'));

    expect(lines).toContain('before *** after');
  });

  it('does not corrupt log lines when the bridge token is an empty string', async () => {
    // Regression guard: an empty token must hit the `: line` branch, not split('') into characters.
    const lines: string[] = [];
    const sup = new JvmSupervisor({ bridgeToken: '', onLog: (line) => lines.push(line) });
    await sup.start();

    children[0].stdout.emit('data', Buffer.from('hello world\n'));

    expect(lines).toContain('hello world');
  });

  it('splits a single multi-line stdout buffer into one onLog call per non-empty line', async () => {
    // The token tests only ever emit a single line, so the split(/\r?\n/) + length===0 skip is
    // otherwise unverified. A regression here would interleave/drop log lines AND defeat per-line
    // token scrubbing across a multi-line stack trace. CRLF and LF must both split; the trailing
    // empty segment after the final '\n' must be dropped.
    const lines: string[] = [];
    const sup = new JvmSupervisor({ onLog: (line) => lines.push(line) });
    await sup.start();

    children[0].stdout.emit('data', Buffer.from('line one\r\nline two\nline three\n'));

    expect(lines).toEqual(['line one', 'line two', 'line three']);
  });
});

describe('JvmSupervisor.stop — hard-kill escalation', () => {
  // These tests drive the SIGTERM-then-escalate branch, which depends on the 4s waitForExit timer
  // firing. start() resolves synchronously (first /health poll is ok) so it does not touch the fake
  // clock; only the stop() escalation does, which is why fake timers are safe here.
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('escalates to taskkill /T /F (after SIGTERM) when SIGTERM does not exit within the grace window', async () => {
    const platform = process.platform;
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true });
    try {
      const sup = new JvmSupervisor({ onLog: () => {} });
      await sup.start();
      spawnMock.mockClear(); // ignore the spawn from start(); only count the taskkill spawn

      // Never emit 'exit': waitForExit's 4s timer must fire and return false.
      const stopped = sup.stop();
      await vi.advanceTimersByTimeAsync(4_000); // cross the grace window deterministically
      await stopped;

      // Graceful SIGTERM is attempted FIRST (not a straight SIGKILL)...
      expect(children[0].kill).toHaveBeenCalledWith('SIGTERM');
      // ...then the hard tree-kill via taskkill — the kill-on-quit guarantee that frees :3223/:3224.
      expect(spawnMock).toHaveBeenCalledWith(
        'taskkill',
        ['/PID', '4321', '/T', '/F'],
        { windowsHide: true },
      );
    } finally {
      Object.defineProperty(process, 'platform', { value: platform, configurable: true });
    }
  });

  it('falls back to SIGKILL on non-win32 when there is no taskkill', async () => {
    const platform = process.platform;
    Object.defineProperty(process, 'platform', { value: 'linux', configurable: true });
    try {
      const sup = new JvmSupervisor({ onLog: () => {} });
      await sup.start();
      spawnMock.mockClear();

      const stopped = sup.stop();
      await vi.advanceTimersByTimeAsync(4_000);
      await stopped;

      // The else arm of the win32 check: SIGKILL, and crucially no taskkill spawn.
      expect(children[0].kill).toHaveBeenNthCalledWith(1, 'SIGTERM');
      expect(children[0].kill).toHaveBeenCalledWith('SIGKILL');
      expect(spawnMock).not.toHaveBeenCalled();
    } finally {
      Object.defineProperty(process, 'platform', { value: platform, configurable: true });
    }
  });

  it('returns immediately and nulls the handle when no child is running (and stays a no-op)', async () => {
    const sup = new JvmSupervisor({ onLog: () => {} });

    // stop() before any start(): the child-null / pid-undefined guard must early-return.
    await expect(sup.stop()).resolves.toBeUndefined();
    // A second stop() must also be a no-op — guards double-quit / quit-before-ready.
    await expect(sup.stop()).resolves.toBeUndefined();

    expect(spawnMock).not.toHaveBeenCalled(); // never spawned a core, never spawned taskkill
  });
});
