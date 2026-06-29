import { describe, expect, it, vi } from 'vitest';
import { SupervisionController, type ChildExit, type SupervisionDeps } from './supervision';

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason?: unknown) => void;
}
function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
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
    // A core that never recovers (startCore keeps failing) must exhaust the budget. A successful restart
    // would reset the counter, so budget exhaustion is specifically the crash-loop-without-recovery case.
    const deps = makeDeps({
      startCore: vi.fn(async () => {
        throw new Error('health timeout');
      }),
    });
    const controller = new SupervisionController(deps, 2);

    await controller.handleCoreCrash(exit); // attempt 1 (restart fails -> notifyDown)
    await controller.handleCoreCrash(exit); // attempt 2 (restart fails -> notifyDown)
    await controller.handleCoreCrash(exit); // budget exhausted -> notifyDown

    expect(deps.startCore).toHaveBeenCalledTimes(2);
    expect(deps.notifyDown).toHaveBeenCalledTimes(3);
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

  it('consumes a second restart attempt for a crash that lands mid-restart (budget honored)', async () => {
    // The restart-then-immediately-die window: restart #1 is in flight when the just-restarted core
    // crashes again. That re-crash must NOT be discarded — it should drive a second restart so the
    // maxRestarts budget is actually used (the bug collapsed maxRestarts=2 to one effective attempt).
    const calls: Array<Deferred<void>> = [];
    const deps = makeDeps({
      startCore: vi.fn(() => {
        const d = deferred<void>();
        calls.push(d);
        return d.promise;
      }),
    });
    const controller = new SupervisionController(deps, 2);

    const crash = controller.handleCoreCrash(exit); // attempt 1: suspends on startCore #1
    await flush();
    expect(deps.startCore).toHaveBeenCalledTimes(1);

    await controller.handleCoreCrash(exit); // re-crash queued while #1 is in flight
    expect(deps.startCore).toHaveBeenCalledTimes(1); // still only one startCore in flight

    calls[0].reject(new Error('superseded')); // #1 fails (the re-crash killed the restarting core)
    await flush();
    expect(deps.startCore).toHaveBeenCalledTimes(2); // the queued re-crash drove attempt 2

    calls[1].resolve(); // attempt 2 recovers
    await crash;
    await flush();
    expect(deps.notifyDown).not.toHaveBeenCalled();
    expect(deps.startObs).toHaveBeenCalledTimes(1); // OBS relaunched after the successful restart
  });

  it('still gives up and notifies once a mid-restart re-crash exhausts the budget', async () => {
    const calls: Array<Deferred<void>> = [];
    const deps = makeDeps({
      startCore: vi.fn(() => {
        const d = deferred<void>();
        calls.push(d);
        return d.promise;
      }),
    });
    const controller = new SupervisionController(deps, 2);

    const crash = controller.handleCoreCrash(exit); // attempt 1
    await flush();
    await controller.handleCoreCrash(exit); // re-crash queued
    calls[0].reject(new Error('superseded')); // #1 fails -> re-loop to attempt 2
    await flush();
    expect(deps.startCore).toHaveBeenCalledTimes(2);

    calls[1].reject(new Error('superseded')); // attempt 2 also fails -> budget exhausted
    await crash;
    expect(deps.notifyDown).toHaveBeenCalledTimes(1);
    expect(deps.startObs).not.toHaveBeenCalled();
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

  it('resets the core restart budget after a successful restart, so a later crash still restarts', async () => {
    // Over a long-lived tray session each restart that recovers should hand the budget back; otherwise
    // the core stops being restarted after maxRestarts LIFETIME crashes even though every prior one recovered.
    const deps = makeDeps();
    const controller = new SupervisionController(deps, 2);

    // Two successful restarts (each resolves startCore) — without the reset these would exhaust the budget.
    await controller.handleCoreCrash(exit);
    await flush();
    await controller.handleCoreCrash(exit);
    await flush();
    expect(deps.startCore).toHaveBeenCalledTimes(2);
    expect(deps.notifyDown).not.toHaveBeenCalled();

    // A third, much-later crash must STILL restart (budget was reset by the prior recoveries).
    await controller.handleCoreCrash(exit);
    await flush();
    expect(deps.startCore).toHaveBeenCalledTimes(3);
    expect(deps.notifyDown).not.toHaveBeenCalled();
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
    // A failing startCore keeps the budget from resetting, so maxRestarts=1 exhausts after one attempt.
    const coreDeps = makeDeps({
      startCore: vi.fn(async () => {
        throw new Error('health timeout');
      }),
    });
    const coreController = new SupervisionController(coreDeps, 1);

    await coreController.handleCoreCrash(exit); // attempt 1 (restart fails)
    await coreController.handleCoreCrash(exit); // budget exhausted

    expect(coreDeps.startCore).toHaveBeenCalledTimes(1);
    expect(coreDeps.notifyDown).toHaveBeenCalledTimes(2);

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
