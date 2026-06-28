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

  it('still restarts the core when the orphaned-OBS teardown rejects', async () => {
    // OBS teardown is best-effort cleanup of an orphaned process; a flaky stopObs() rejection must
    // not propagate out of handleCoreCrash and abort the core restart (which would leave recording
    // silently dead on a tray-hidden app).
    const deps = makeDeps({
      stopObs: vi.fn(async () => {
        throw new Error('obs stop failed');
      }),
    });
    const controller = new SupervisionController(deps);

    await controller.handleCoreCrash(exit);
    await flush();

    expect(deps.stopObs).toHaveBeenCalledTimes(1);
    expect(deps.startCore).toHaveBeenCalledTimes(1); // restart proceeded despite the rejection
    expect(deps.startObs).toHaveBeenCalledTimes(1); // relaunched via launchObs
    expect(deps.notifyDown).not.toHaveBeenCalled();
  });

  it('still notifies the user when the failed-restart reap also rejects', async () => {
    // Failed-restart branch: a lingering unhealthy JVM whose reap also fails must not swallow the
    // user-facing notifyDown nor reject the crash handler (which would strand coreRestarting).
    const deps = makeDeps({
      startCore: vi.fn(async () => {
        throw new Error('health timeout');
      }),
      stopCore: vi.fn(async () => {
        throw new Error('reap failed');
      }),
    });
    const controller = new SupervisionController(deps);

    await expect(controller.handleCoreCrash(exit)).resolves.toBeUndefined();

    expect(deps.stopCore).toHaveBeenCalledTimes(1);
    expect(deps.notifyDown).toHaveBeenCalledTimes(1);
    expect(deps.startObs).not.toHaveBeenCalled();
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

  it('never touches the core on an OBS crash, even once the OBS budget is exhausted', async () => {
    // The two crash domains are separate: an OBS-only crash is a bounded relaunch and must never
    // trigger a core reap/restart or a user-facing core-down notice.
    const deps = makeDeps();
    const controller = new SupervisionController(deps, 2);

    controller.handleObsCrash(exit);
    await flush();
    expect(deps.startObs).toHaveBeenCalledTimes(1);
    expect(deps.startCore).not.toHaveBeenCalled();
    expect(deps.stopCore).not.toHaveBeenCalled();
    expect(deps.notifyDown).not.toHaveBeenCalled();

    // Exhaust the OBS budget; the core invariant must still hold.
    controller.handleObsCrash(exit);
    await flush();
    controller.handleObsCrash(exit); // budget exhausted
    await flush();

    expect(deps.startObs).toHaveBeenCalledTimes(2);
    expect(deps.startCore).not.toHaveBeenCalled();
    expect(deps.stopCore).not.toHaveBeenCalled();
    expect(deps.notifyDown).not.toHaveBeenCalled();
  });

  it('honors a custom maxRestarts on both the core and OBS budgets', async () => {
    // Every other test passes 2 (== the default), so the constructor param flowing into the budget
    // checks is otherwise indistinguishable from the hardcoded default. maxRestarts=1 proves it.
    const coreDeps = makeDeps();
    const coreController = new SupervisionController(coreDeps, 1);

    await coreController.handleCoreCrash(exit); // attempt 1
    await coreController.handleCoreCrash(exit); // budget exhausted

    expect(coreDeps.startCore).toHaveBeenCalledTimes(1);
    expect(coreDeps.notifyDown).toHaveBeenCalledTimes(1);

    const obsDeps = makeDeps();
    const obsController = new SupervisionController(obsDeps, 1);

    obsController.handleObsCrash(exit); // attempt 1
    await flush();
    obsController.handleObsCrash(exit); // budget exhausted
    await flush();

    expect(obsDeps.startObs).toHaveBeenCalledTimes(1);
  });

  it('relaunches OBS once on a fresh budget when an OBS crash lands mid core-restart', async () => {
    // Interleave the two state machines: an OBS crash arrives while the core restart is suspended on
    // its health wait. It increments the OBS budget but the in-flight launch guard prevents orphaning
    // a second OBS; once the core comes up the post-restart relaunch fires on a fresh budget.
    const coreStart = deferred<void>();
    const obsStart = deferred<void>();
    const deps = makeDeps({
      startCore: vi.fn(() => coreStart.promise),
      startObs: vi.fn(() => obsStart.promise),
    });
    const controller = new SupervisionController(deps, 2);

    const crash = controller.handleCoreCrash(exit); // suspends awaiting startCore (coreRestarting=true)
    await flush();
    expect(deps.startCore).toHaveBeenCalledTimes(1);

    // An OBS crash lands mid core-restart: it increments the OBS budget and tries to relaunch, but the
    // obsLaunching guard (no OBS launch in flight yet here) means exactly one startObs is attempted.
    controller.handleObsCrash(exit);
    await flush();
    expect(deps.startObs).toHaveBeenCalledTimes(1);

    // The core comes up: obsRestartAttempts resets and the post-restart relaunch fires. The previous
    // launch is no longer in flight (resolve it first), so this is a distinct startObs on a fresh budget.
    obsStart.resolve();
    await flush();
    coreStart.resolve();
    await crash;
    await flush();

    expect(deps.startObs).toHaveBeenCalledTimes(2);

    // Fresh budget after the restart: OBS can crash and relaunch maxRestarts more times.
    controller.handleObsCrash(exit);
    await flush();
    controller.handleObsCrash(exit);
    await flush();
    controller.handleObsCrash(exit); // budget exhausted
    await flush();

    expect(deps.startObs).toHaveBeenCalledTimes(4); // 2 (pre/post restart) + 2 fresh-budget relaunches
  });
});
