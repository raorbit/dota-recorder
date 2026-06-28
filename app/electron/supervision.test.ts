import { describe, expect, it, vi } from 'vitest';
import { SupervisionController, type ChildExit, type SupervisionDeps } from './supervision';

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
}
function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

// Let any pending microtasks (awaited deps + the un-awaited `void this.launchObs()`) settle.
const flush = (): Promise<void> => new Promise((r) => setTimeout(r, 0));

const exit: ChildExit = { code: 1, signal: null };

function makeDeps(overrides: Partial<SupervisionDeps> = {}): SupervisionDeps {
  return {
    startCore: vi.fn(async () => {}),
    stopCore: vi.fn(async () => {}),
    startObs: vi.fn(async () => {}),
    stopObs: vi.fn(async () => {}),
    notifyDown: vi.fn(),
    log: vi.fn(),
    isShuttingDown: vi.fn(() => false),
    ...overrides,
  };
}

describe('SupervisionController — core crash', () => {
  it('stops the orphaned OBS, restarts the core, then relaunches OBS', async () => {
    const deps = makeDeps();
    const controller = new SupervisionController(deps);

    await controller.handleCoreCrash(exit);
    await flush();

    expect(deps.stopObs).toHaveBeenCalledTimes(1);
    expect(deps.startCore).toHaveBeenCalledTimes(1);
    expect(deps.startObs).toHaveBeenCalledTimes(1); // relaunched via launchObs
    expect(deps.notifyDown).not.toHaveBeenCalled();
  });

  it('stops after the bounded restart budget and notifies the user', async () => {
    const deps = makeDeps();
    const controller = new SupervisionController(deps, 2);

    await controller.handleCoreCrash(exit); // attempt 1
    await controller.handleCoreCrash(exit); // attempt 2
    await controller.handleCoreCrash(exit); // budget exhausted

    expect(deps.startCore).toHaveBeenCalledTimes(2);
    expect(deps.notifyDown).toHaveBeenCalledTimes(1);
  });

  it('does not run a second startCore when a crash lands during an in-flight restart', async () => {
    const pending = deferred<void>();
    const deps = makeDeps({ startCore: vi.fn(() => pending.promise) });
    const controller = new SupervisionController(deps);

    const first = controller.handleCoreCrash(exit); // suspends awaiting startCore
    await flush();
    await controller.handleCoreCrash(exit); // re-entrant: should bail, NOT start a second core

    expect(deps.startCore).toHaveBeenCalledTimes(1);
    expect(deps.log).toHaveBeenCalledWith(expect.stringContaining('restart is already running'));

    pending.resolve();
    await first;
  });

  it('reaps the half-started core and notifies when a restart fails', async () => {
    const deps = makeDeps({
      startCore: vi.fn(async () => {
        throw new Error('health timeout');
      }),
    });
    const controller = new SupervisionController(deps);

    await controller.handleCoreCrash(exit);

    expect(deps.stopCore).toHaveBeenCalledTimes(1); // reap the lingering JVM holding the ports
    expect(deps.notifyDown).toHaveBeenCalledTimes(1);
    expect(deps.startObs).not.toHaveBeenCalled(); // no relaunch on a failed restart
  });

  it('does nothing once a real shutdown is underway', async () => {
    const deps = makeDeps({ isShuttingDown: vi.fn(() => true) });
    const controller = new SupervisionController(deps);

    await controller.handleCoreCrash(exit);

    expect(deps.stopObs).not.toHaveBeenCalled();
    expect(deps.startCore).not.toHaveBeenCalled();
  });
});

describe('SupervisionController — OBS crash & launch', () => {
  it('bounded-relaunches OBS and then leaves it down', async () => {
    const deps = makeDeps();
    const controller = new SupervisionController(deps, 2);

    controller.handleObsCrash(exit);
    await flush();
    controller.handleObsCrash(exit);
    await flush();
    controller.handleObsCrash(exit); // budget exhausted
    await flush();

    expect(deps.startObs).toHaveBeenCalledTimes(2);
    expect(deps.log).toHaveBeenCalledWith(expect.stringContaining('exceeded restart attempts'));
  });

  it('serializes launches: a second launch while one is in flight is skipped', async () => {
    const pending = deferred<void>();
    const deps = makeDeps({ startObs: vi.fn(() => pending.promise) });
    const controller = new SupervisionController(deps);

    const first = controller.launchObs(); // startObs called, still in flight
    await controller.launchObs(); // guarded: skipped

    expect(deps.startObs).toHaveBeenCalledTimes(1);
    expect(deps.log).toHaveBeenCalledWith(expect.stringContaining('launch already in progress'));

    pending.resolve();
    await first;
  });

  it('does nothing on an OBS crash once shutting down', () => {
    const deps = makeDeps({ isShuttingDown: vi.fn(() => true) });
    const controller = new SupervisionController(deps);

    controller.handleObsCrash(exit);

    expect(deps.startObs).not.toHaveBeenCalled();
  });

  it('gives a freshly-restarted core a fresh OBS restart budget', async () => {
    const deps = makeDeps();
    const controller = new SupervisionController(deps, 2);

    // Exhaust the OBS budget (2 relaunches, then nothing).
    controller.handleObsCrash(exit);
    await flush();
    controller.handleObsCrash(exit);
    await flush();
    controller.handleObsCrash(exit);
    await flush();
    expect(deps.startObs).toHaveBeenCalledTimes(2);

    // A core restart resets the OBS budget and relaunches OBS (startObs #3)...
    await controller.handleCoreCrash(exit);
    await flush();
    // ...so OBS can crash and relaunch once more (startObs #4).
    controller.handleObsCrash(exit);
    await flush();

    expect(deps.startObs).toHaveBeenCalledTimes(4);
  });
});
