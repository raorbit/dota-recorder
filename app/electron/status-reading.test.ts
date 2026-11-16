import { describe, expect, it } from 'vitest';
import { idleForInstall, parseStatusBody, toObsDemandReading } from './status-reading';

const lowDisk = {
  freeBytes: 1_073_741_824,
  thresholdBytes: 5_368_709_120,
  message: 'Low disk space',
  observedAtMs: 1_700_000_000_000,
};

describe('parseStatusBody', () => {
  it('reads the fields the shell acts on from a full body', () => {
    const reading = parseStatusBody({
      gsi: { connected: true, lastFrameAgoMs: 5 },
      obs: { connected: true, sceneActive: true, recording: true, captureBlack: false, wanted: true },
      fsm: { state: 'RECORDING', activeMatchId: 7 },
      storage: { lowDisk },
      deletes: { matchIds: [7], clipIds: [] },
      kept: { frames: 1 },
    });
    expect(reading).toEqual({
      wanted: true,
      recording: true,
      fsmState: 'RECORDING',
      lowDisk,
      deletesPending: true,
      framesKept: true,
    });
  });

  it('reads a missing wanted as wanted and a missing storage as healthy (older cores)', () => {
    const reading = parseStatusBody({ obs: { recording: false }, fsm: { state: 'IDLE' } });
    expect(reading).toEqual({
      wanted: true,
      recording: false,
      fsmState: 'IDLE',
      lowDisk: null,
      deletesPending: false,
      framesKept: false,
    });
  });

  it('reads whether the core still has a recording or clip delete to do', () => {
    const pending = (deletes: unknown): boolean | undefined =>
      parseStatusBody({ fsm: { state: 'IDLE' }, deletes })?.deletesPending;
    expect(pending({ matchIds: [], clipIds: [5] })).toBe(true);
    expect(pending({ matchIds: [7] })).toBe(true);
    expect(pending({ matchIds: [], clipIds: [] })).toBe(false);
    expect(pending({ matchIds: 'lots' })).toBe(false);
    expect(pending(undefined)).toBe(false);
  });

  it('reads whether the core keeps a frame no window has taken yet', () => {
    const kept = (value: unknown): boolean | undefined =>
      parseStatusBody({ fsm: { state: 'IDLE' }, kept: value })?.framesKept;
    expect(kept({ frames: 2 })).toBe(true);
    expect(kept({ frames: 0 })).toBe(false);
    expect(kept({ frames: '2' })).toBe(false);
    expect(kept({})).toBe(false);
    expect(kept(null)).toBe(false);
    expect(kept(undefined)).toBe(false);
  });

  it('reads storage.lowDisk null as healthy and drops a malformed observation', () => {
    expect(parseStatusBody({ fsm: { state: 'IDLE' }, storage: { lowDisk: null } })?.lowDisk).toBeNull();
    expect(
      parseStatusBody({ fsm: { state: 'IDLE' }, storage: { lowDisk: { freeBytes: 'lots' } } })
        ?.lowDisk,
    ).toBeNull();
  });

  it('rejects a body that says nothing about the recorder', () => {
    expect(parseStatusBody(null)).toBeNull();
    expect(parseStatusBody('ok')).toBeNull();
    expect(parseStatusBody({ obs: { recording: true } })).toBeNull();
    expect(parseStatusBody({ fsm: { state: 3 } })).toBeNull();
  });
});

describe('toObsDemandReading', () => {
  it('is busy while recording or whenever the FSM is not IDLE, and carries wanted through', () => {
    const base = {
      wanted: false,
      recording: false,
      fsmState: 'IDLE',
      lowDisk: null,
      deletesPending: false,
      framesKept: false,
    };
    expect(toObsDemandReading(base)).toEqual({ wanted: false, busy: false });
    expect(toObsDemandReading({ ...base, recording: true })).toEqual({ wanted: false, busy: true });
    expect(toObsDemandReading({ ...base, fsmState: 'ARMED' })).toEqual({ wanted: false, busy: true });
    expect(toObsDemandReading({ ...base, wanted: true })).toEqual({ wanted: true, busy: false });
  });
});

describe('idleForInstall', () => {
  const idle = {
    wanted: false,
    recording: false,
    fsmState: 'IDLE',
    lowDisk: null,
    deletesPending: false,
    framesKept: false,
  };

  it('is idle only with nothing recording, OBS not wanted and no delete still to do', () => {
    expect(idleForInstall(idle)).toBe(true);
    expect(idleForInstall({ ...idle, fsmState: 'STOPPING' })).toBe(false);
    expect(idleForInstall({ ...idle, recording: true })).toBe(false);
    expect(idleForInstall({ ...idle, wanted: true })).toBe(false);
  });

  it('waits while the core still has a delete to do, which stopping it would drop', () => {
    expect(idleForInstall({ ...idle, deletesPending: true })).toBe(false);
  });

  it('waits while the core keeps a delete failure no window has heard of yet', () => {
    expect(idleForInstall({ ...idle, framesKept: true })).toBe(false);
  });
});
