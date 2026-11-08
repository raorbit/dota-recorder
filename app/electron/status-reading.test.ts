import { describe, expect, it } from 'vitest';
import { parseStatusBody, toObsDemandReading } from './status-reading';

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
    });
    expect(reading).toEqual({ wanted: true, recording: true, fsmState: 'RECORDING', lowDisk });
  });

  it('reads a missing wanted as wanted and a missing storage as healthy (older cores)', () => {
    const reading = parseStatusBody({ obs: { recording: false }, fsm: { state: 'IDLE' } });
    expect(reading).toEqual({ wanted: true, recording: false, fsmState: 'IDLE', lowDisk: null });
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
    const base = { wanted: false, recording: false, fsmState: 'IDLE', lowDisk: null };
    expect(toObsDemandReading(base)).toEqual({ wanted: false, busy: false });
    expect(toObsDemandReading({ ...base, recording: true })).toEqual({ wanted: false, busy: true });
    expect(toObsDemandReading({ ...base, fsmState: 'ARMED' })).toEqual({ wanted: false, busy: true });
    expect(toObsDemandReading({ ...base, wanted: true })).toEqual({ wanted: true, busy: false });
  });
});
