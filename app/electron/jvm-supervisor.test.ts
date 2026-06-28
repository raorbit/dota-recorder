import { EventEmitter } from 'node:events';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Hoisted so the (hoisted) vi.mock factory below can reference it.
const { spawnMock } = vi.hoisted(() => ({ spawnMock: vi.fn() }));

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
      json: async () => ({ status: healthOk ? 'ok' : 'starting' }),
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
});
