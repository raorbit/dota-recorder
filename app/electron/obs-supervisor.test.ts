import { EventEmitter } from 'node:events';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { spawnMock } = vi.hoisted(() => ({ spawnMock: vi.fn() }));

vi.mock('node:child_process', async (importOriginal) => {
  const actual = await importOriginal<typeof import('node:child_process')>();
  return {
    ...actual,
    spawn: (...args: unknown[]) => spawnMock(...args),
    // The firewall scoping shells out to netsh; pretend it succeeds so start() proceeds to spawn OBS.
    execFile: (_file: unknown, _args: unknown, _opts: unknown, cb: (err: unknown) => void) => cb(null),
  };
});
vi.mock('node:fs', async (importOriginal) => {
  const actual = await importOriginal<typeof import('node:fs')>();
  return { ...actual, existsSync: () => true };
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

beforeEach(() => {
  children = [];
  obsConnected = true;
  spawnMock.mockReset();
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
});
