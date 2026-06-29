import { EventEmitter } from 'node:events';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Hoisted so the (hoisted) vi.mock factories below can reference them.
// `execFileImpl` is mutable so individual tests can swap the netsh success/failure branch.
const { spawnMock, execFileMock, existsState, execFileImpl } = vi.hoisted(() => {
  const execFileImpl: {
    fn: (file: unknown, args: unknown, opts: unknown, cb: (err: unknown) => void) => void;
  } = {
    // Default: netsh exits 0 (success) so start() proceeds to spawn OBS.
    fn: (_file, _args, _opts, cb) => cb(null),
  };
  return {
    spawnMock: vi.fn(),
    execFileMock: vi.fn((...args: unknown[]) =>
      execFileImpl.fn(args[0], args[1], args[2], args[3] as (err: unknown) => void),
    ),
    // `exists` is mutable so a test can flip obs64.exe to "missing".
    existsState: { exists: true },
    execFileImpl,
  };
});

vi.mock('node:child_process', async (importOriginal) => {
  const actual = await importOriginal<typeof import('node:child_process')>();
  return {
    ...actual,
    spawn: (...args: unknown[]) => spawnMock(...args),
    execFile: (...args: unknown[]) => execFileMock(...args),
  };
});
vi.mock('node:fs', async (importOriginal) => {
  const actual = await importOriginal<typeof import('node:fs')>();
  return { ...actual, existsSync: () => existsState.exists };
});
vi.mock('./job-object', () => ({ assign: vi.fn() }));
vi.mock('./paths', () => ({
  BRIDGE_BASE: 'http://127.0.0.1:3224',
  BRIDGE_TOKEN_HEADER: 'X-Dotarec-Token',
}));

import { ObsSupervisor } from './obs-supervisor';

class FakeChild extends EventEmitter {
  pid: number | undefined = 8765;
  stdout = new EventEmitter();
  stderr = new EventEmitter();
  kill = vi.fn();
}

const baseOpts = {
  obsDir: 'C:/obs',
  port: 4466,
  password: 'pw',
  collection: 'c',
  profile: 'p',
  scene: 's',
} as const;

let children: FakeChild[];
let obsConnected: boolean;
let originalPlatform: PropertyDescriptor | undefined;

/** Force process.platform for the firewall + taskkill branches that gate on win32. */
function setPlatform(platform: NodeJS.Platform): void {
  Object.defineProperty(process, 'platform', { value: platform, configurable: true });
}

beforeEach(() => {
  children = [];
  obsConnected = true;
  existsState.exists = true;
  execFileImpl.fn = (_file, _args, _opts, cb: (err: unknown) => void) => cb(null);
  spawnMock.mockReset();
  execFileMock.mockClear();
  originalPlatform = Object.getOwnPropertyDescriptor(process, 'platform');
  spawnMock.mockImplementation(() => {
    const child = new FakeChild();
    children.push(child);
    return child;
  });
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: true,
      json: async () => ({ obs: { connected: obsConnected } }),
    })),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  if (originalPlatform) Object.defineProperty(process, 'platform', originalPlatform);
});

const flush = (ms = 0): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

describe('ObsSupervisor', () => {
  it('resolves once the core reports OBS connected', async () => {
    const sup = new ObsSupervisor({ ...baseOpts, onLog: () => {} });
    await expect(sup.start()).resolves.toBeUndefined();
    expect(spawnMock).toHaveBeenCalledTimes(1);
  });

  it('fires onUnexpectedExit when OBS exits without a stop()', async () => {
    const onUnexpectedExit = vi.fn();
    const sup = new ObsSupervisor({ ...baseOpts, onLog: () => {}, onUnexpectedExit });
    await sup.start();

    children[0].emit('exit', 3, null);

    expect(onUnexpectedExit).toHaveBeenCalledWith({ code: 3, signal: null });
  });

  it('does NOT fire onUnexpectedExit when stop() requested the exit', async () => {
    const onUnexpectedExit = vi.fn();
    const sup = new ObsSupervisor({ ...baseOpts, onLog: () => {}, onUnexpectedExit });
    await sup.start();

    const stopped = sup.stop();
    children[0].emit('exit', 0, 'SIGTERM');
    await stopped;

    expect(onUnexpectedExit).not.toHaveBeenCalled();
    expect(children[0].kill).toHaveBeenCalledWith('SIGTERM');
  });

  it('aborts the readiness poll promptly when OBS crashes during startup (generation guard)', async () => {
    obsConnected = false; // /status never reports connected
    const sup = new ObsSupervisor({ ...baseOpts, onLog: () => {}, healthTimeoutMs: 5_000 });

    const starting = sup.start();
    await flush(30);
    children[0].emit('exit', 1, null);

    await expect(starting).rejects.toThrow(/superseded/i);
  });

  // Case 1: obs64.exe missing -> descriptive throw before any side effect.
  it('throws a descriptive error and never spawns when obs64.exe is missing', async () => {
    existsState.exists = false; // simulate the wrong-obsSourceDir packaged-layout failure
    const sup = new ObsSupervisor({ ...baseOpts, onLog: () => {} });

    await expect(sup.start()).rejects.toThrow(/OBS executable not found/);
    // The path in the message must point at the canonical bin/64bit/obs64.exe location.
    await expect(sup.start()).rejects.toThrow(/bin[\\/]64bit[\\/]obs64\.exe/);

    // No OBS process and no firewall mutation when the precondition fails.
    expect(spawnMock).not.toHaveBeenCalled();
    expect(execFileMock).not.toHaveBeenCalled();
  });

  // Case 1b: obs64.exe present (existsSync passes) but the spawn itself fails (EACCES / corrupt binary
  // / TOCTOU delete). The 'error' event must reject start() rather than become an uncaughtException
  // that kills the Electron main process.
  it('rejects start() with the real error (not crash) when obs64.exe fails to launch', async () => {
    obsConnected = false; // keep the readiness poll going so the launch error wins the race
    const onUnexpectedExit = vi.fn();
    const sup = new ObsSupervisor({ ...baseOpts, onLog: () => {}, onUnexpectedExit, healthTimeoutMs: 5_000 });

    const starting = sup.start();
    await flush(10);
    children[0].emit('error', Object.assign(new Error('spawn obs64.exe EACCES'), { code: 'EACCES' }));

    await expect(starting).rejects.toThrow(/EACCES/);
    expect(onUnexpectedExit).not.toHaveBeenCalled(); // a launch failure rejects start(), not a phantom crash
  });

  it('logs a post-startup OBS error without restarting or orphaning (stop still reaps)', async () => {
    // After start() succeeds the 'error' listener stays benign: it must not null the handle (stop()
    // would then never reap the live OBS) nor fire onUnexpectedExit (a spurious relaunch).
    const onUnexpectedExit = vi.fn();
    const sup = new ObsSupervisor({ ...baseOpts, onLog: () => {}, onUnexpectedExit });
    await sup.start();

    children[0].emit('error', new Error('post-startup hiccup'));
    expect(onUnexpectedExit).not.toHaveBeenCalled();

    const stopped = sup.stop();
    children[0].emit('exit', 0, 'SIGTERM'); // satisfy the graceful-exit wait
    await stopped;
    expect(children[0].kill).toHaveBeenCalledWith('SIGTERM'); // stop() still saw the live child and reaped it
  });

  // Case 2: firewall scoping success branch.
  it('logs the loopback-scoped success message and runs netsh delete-then-add', async () => {
    setPlatform('win32');
    const lines: string[] = [];
    const sup = new ObsSupervisor({ ...baseOpts, onLog: (line) => lines.push(line) });

    await sup.start();

    expect(lines).toContain('firewall: scoped obs-websocket :4466 to loopback');
    // Idempotent: a delete then an add, both naming the managed port.
    expect(execFileMock).toHaveBeenCalledTimes(2);
    const firstArgs = execFileMock.mock.calls[0][1] as string[];
    const secondArgs = execFileMock.mock.calls[1][1] as string[];
    expect(firstArgs).toContain('delete');
    expect(secondArgs).toContain('add');
    expect(firstArgs).toContain('localport=4466');
    expect(secondArgs).toContain('localport=4466');
    expect(secondArgs.some((a) => a.includes('4466') && a.startsWith('name='))).toBe(true);
  });

  // Case 3: firewall scoping failure branch — must warn but still launch OBS.
  it('logs the admin-needed warning yet still spawns OBS when netsh add fails', async () => {
    setPlatform('win32');
    let callCount = 0;
    // delete (call 1) succeeds; add (call 2) fails as if unelevated.
    execFileImpl.fn = (_file, _args, _opts, cb: (err: unknown) => void) => {
      callCount += 1;
      cb(callCount >= 2 ? new Error('elevation required') : null);
    };
    const lines: string[] = [];
    const sup = new ObsSupervisor({ ...baseOpts, onLog: (line) => lines.push(line) });

    await expect(sup.start()).resolves.toBeUndefined();

    expect(spawnMock).toHaveBeenCalledTimes(1); // OBS still launched despite the firewall failure
    expect(lines.some((l) => /could not scope :4466 to loopback \(needs admin\)|LAN-reachable/.test(l))).toBe(
      true,
    );
  });

  // Case 4: stop() escalates to taskkill when SIGTERM doesn't exit OBS in the grace window.
  it('escalates to taskkill on win32 when SIGTERM does not exit OBS in time', async () => {
    setPlatform('win32');
    const sup = new ObsSupervisor({ ...baseOpts, onLog: () => {} });
    await sup.start();
    spawnMock.mockClear(); // ignore the OBS launch spawn; we only care about the taskkill spawn

    vi.useFakeTimers();
    const stopped = sup.stop(); // never emit 'exit' so the 4s grace window lapses

    // SIGTERM is attempted first, before any escalation.
    expect(children[0].kill).toHaveBeenCalledWith('SIGTERM');

    await vi.advanceTimersByTimeAsync(4_000); // lapse the grace window
    await stopped;

    expect(spawnMock).toHaveBeenCalledTimes(1);
    const [cmd, args] = spawnMock.mock.calls[0];
    expect(cmd).toBe('taskkill');
    expect(args).toEqual(['/PID', '8765', '/T', '/F']);
  });

  // Case 5: exact portable arg list and cwd = bin/64bit.
  it('spawns obs64.exe with the exact portable arg list and cwd = bin/64bit', async () => {
    const sup = new ObsSupervisor({ ...baseOpts, onLog: () => {} });
    await sup.start();

    const [exe, args, opts] = spawnMock.mock.calls[0] as [string, string[], { cwd: string }];
    expect(exe).toMatch(/bin[\\/]64bit[\\/]obs64\.exe$/);
    expect(args).toEqual([
      '--portable',
      '--multi',
      '--minimize-to-tray',
      '--disable-updater',
      '--disable-missing-files-check',
      '--collection',
      'c',
      '--profile',
      'p',
      '--scene',
      's',
      '--websocket_port',
      '4466',
      '--websocket_password',
      'pw',
      '--websocket_ipv4_only',
    ]);
    expect(opts.cwd).toMatch(/bin[\\/]64bit$/);
  });
});
