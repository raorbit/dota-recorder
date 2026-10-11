import { describe, expect, it, vi } from 'vitest';
import {
  OBS_IDLE_STOP_MS,
  OBS_LAUNCH_RETRY_MS,
  ObsDemandController,
  type ObsDemandDeps,
  type ObsDemandReading,
} from './obs-demand';

interface Harness {
  deps: ObsDemandDeps;
  controller: ObsDemandController;
  setReading(reading: ObsDemandReading | null): void;
  advance(ms: number): void;
  state: { running: boolean; launching: boolean; canLaunch: boolean; shuttingDown: boolean };
}

function harness(): Harness {
  let reading: ObsDemandReading | null = { wanted: false, busy: false };
  let clock = 1_000;
  const state = { running: false, launching: false, canLaunch: true, shuttingDown: false };
  const deps: ObsDemandDeps = {
    readStatus: vi.fn(async () => reading),
    isObsRunning: () => state.running,
    isObsLaunching: () => state.launching,
    canLaunch: () => state.canLaunch,
    launchObs: vi.fn(() => {
      state.running = true;
    }),
    stopObs: vi.fn(async () => {
      state.running = false;
    }),
    resetBudget: vi.fn(),
    isShuttingDown: () => state.shuttingDown,
    log: vi.fn(),
    now: () => clock,
  };
  return {
    deps,
    controller: new ObsDemandController(deps),
    setReading: (r) => {
      reading = r;
    },
    advance: (ms) => {
      clock += ms;
    },
    state,
  };
}

const WANTED = { wanted: true, busy: false };
const IDLE = { wanted: false, busy: false };

describe('ObsDemandController', () => {
  it('does not launch OBS while nothing needs it', async () => {
    const h = harness();
    h.setReading(IDLE);

    await h.controller.tick();

    expect(h.deps.launchObs).not.toHaveBeenCalled();
  });

  it('launches OBS once it is wanted, and only once while it runs', async () => {
    const h = harness();
    h.setReading(WANTED);

    await h.controller.tick();
    await h.controller.tick();

    expect(h.deps.launchObs).toHaveBeenCalledTimes(1);
  });

  it('does not double-launch while a launch is in flight', async () => {
    const h = harness();
    h.state.launching = true;
    h.setReading(WANTED);

    await h.controller.tick();

    expect(h.deps.launchObs).not.toHaveBeenCalled();
  });

  it('stops OBS only after it has gone unwanted for the idle window', async () => {
    const h = harness();
    h.setReading(WANTED);
    await h.controller.tick();

    h.setReading(IDLE);
    h.advance(OBS_IDLE_STOP_MS - 1);
    await h.controller.tick();
    expect(h.deps.stopObs).not.toHaveBeenCalled();

    h.advance(1);
    await h.controller.tick();
    expect(h.deps.stopObs).toHaveBeenCalledTimes(1);
    expect(h.deps.resetBudget).toHaveBeenCalled();
  });

  it('never stops OBS under a busy recorder, even if the core reports it unwanted', async () => {
    const h = harness();
    h.state.running = true;
    h.setReading({ wanted: false, busy: true });

    h.advance(OBS_IDLE_STOP_MS * 3);
    await h.controller.tick();

    expect(h.deps.stopObs).not.toHaveBeenCalled();
  });

  it('a renewed need inside the idle window keeps OBS up', async () => {
    const h = harness();
    h.setReading(WANTED);
    await h.controller.tick();

    h.setReading(IDLE);
    h.advance(OBS_IDLE_STOP_MS - 10);
    await h.controller.tick();
    h.setReading(WANTED);
    await h.controller.tick();
    h.setReading(IDLE);
    h.advance(OBS_IDLE_STOP_MS - 10);
    await h.controller.tick();

    expect(h.deps.stopObs).not.toHaveBeenCalled();
  });

  it('leaves OBS alone when the core does not answer', async () => {
    const h = harness();
    h.state.running = true;
    h.setReading(null);

    h.advance(OBS_IDLE_STOP_MS * 3);
    await h.controller.tick();

    expect(h.deps.stopObs).not.toHaveBeenCalled();
    expect(h.deps.launchObs).not.toHaveBeenCalled();
  });

  it('does not stop an OBS that is still launching', async () => {
    const h = harness();
    h.state.running = true;
    h.state.launching = true;
    h.setReading(IDLE);

    h.advance(OBS_IDLE_STOP_MS);
    await h.controller.tick();

    expect(h.deps.stopObs).not.toHaveBeenCalled();
  });

  it('respects a spent crash budget, then retries once a new need starts', async () => {
    const h = harness();
    h.state.canLaunch = false;
    h.setReading(WANTED);
    await h.controller.tick();
    await h.controller.tick();
    expect(h.deps.launchObs).not.toHaveBeenCalled();
    expect(h.deps.log).toHaveBeenCalledTimes(1); // the "gave up" notice is logged once

    // The need ends; after the idle window the budget is reset for the next one.
    h.setReading(IDLE);
    h.advance(OBS_IDLE_STOP_MS);
    (h.deps.resetBudget as ReturnType<typeof vi.fn>).mockImplementation(() => {
      h.state.canLaunch = true;
    });
    await h.controller.tick();

    h.setReading(WANTED);
    await h.controller.tick();
    expect(h.deps.launchObs).toHaveBeenCalledTimes(1);
  });

  it('spaces out retries of a launch that failed outright', async () => {
    const h = harness();
    (h.deps.launchObs as ReturnType<typeof vi.fn>).mockImplementation(() => {
      /* spawn failed: nothing runs */
    });
    h.setReading(WANTED);

    await h.controller.tick();
    h.advance(OBS_LAUNCH_RETRY_MS - 1);
    await h.controller.tick();
    expect(h.deps.launchObs).toHaveBeenCalledTimes(1);

    h.advance(1);
    await h.controller.tick();
    expect(h.deps.launchObs).toHaveBeenCalledTimes(2);
  });

  it('does nothing once shutdown has begun', async () => {
    const h = harness();
    h.state.shuttingDown = true;
    h.setReading(WANTED);

    await h.controller.tick();

    expect(h.deps.readStatus).not.toHaveBeenCalled();
    expect(h.deps.launchObs).not.toHaveBeenCalled();
  });

  it('drops a tick that overlaps a slow one', async () => {
    const h = harness();
    let release!: () => void;
    (h.deps.readStatus as ReturnType<typeof vi.fn>).mockImplementationOnce(
      () =>
        new Promise<ObsDemandReading>((resolve) => {
          release = () => resolve(WANTED);
        }),
    );

    const first = h.controller.tick();
    await h.controller.tick(); // overlaps: dropped
    release();
    await first;

    expect(h.deps.readStatus).toHaveBeenCalledTimes(1);
    expect(h.deps.launchObs).toHaveBeenCalledTimes(1);
  });
});
