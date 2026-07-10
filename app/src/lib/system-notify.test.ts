import { describe, expect, it } from 'vitest';
import type { Status } from '../api/client';
import {
  isOrphanedRecording,
  stepOrphanNotify,
  shouldNotifyDisk,
  INITIAL_ORPHAN_NOTIFY_STATE,
  ORPHAN_NOTIFY_DELAY_MS,
  DISK_NOTIFY_DEBOUNCE_MS,
} from './system-notify';

// A Status with the two fields isOrphanedRecording reads; the snapshot is filled to satisfy the type.
function mkStatus(recording: boolean, fsmState: string): Status {
  return {
    fsmState,
    matchId: null,
    recording,
    gsiConnected: true,
    snapshot: {
      gsi: { connected: true, lastFrameAgoMs: 0 },
      obs: { connected: true, sceneActive: true, recording },
      fsm: { state: fsmState, activeMatchId: null },
    },
  };
}

describe('isOrphanedRecording', () => {
  it('is true when OBS is recording but the FSM is not in RECORDING', () => {
    expect(isOrphanedRecording(mkStatus(true, 'IDLE'))).toBe(true);
  });

  it('is false during a normal recording (OBS recording AND FSM RECORDING)', () => {
    expect(isOrphanedRecording(mkStatus(true, 'RECORDING'))).toBe(false);
  });

  it('is false when OBS is not recording', () => {
    expect(isOrphanedRecording(mkStatus(false, 'IDLE'))).toBe(false);
  });

  it('is false for a null status (socket down — real state unknown)', () => {
    expect(isOrphanedRecording(null)).toBe(false);
  });
});

describe('stepOrphanNotify', () => {
  it('does not fire before the condition has persisted the debounce window', () => {
    const first = stepOrphanNotify(INITIAL_ORPHAN_NOTIFY_STATE, true, 1_000);
    expect(first.fire).toBe(false);
    expect(first.next.since).toBe(1_000);

    // Still active but short of the threshold: no fire, streak clock preserved.
    const mid = stepOrphanNotify(first.next, true, 1_000 + ORPHAN_NOTIFY_DELAY_MS - 1);
    expect(mid.fire).toBe(false);
    expect(mid.next.since).toBe(1_000);
    expect(mid.next.notified).toBe(false);
  });

  it('fires once when the condition has persisted past the window, then latches', () => {
    const first = stepOrphanNotify(INITIAL_ORPHAN_NOTIFY_STATE, true, 1_000);
    const fired = stepOrphanNotify(first.next, true, 1_000 + ORPHAN_NOTIFY_DELAY_MS);
    expect(fired.fire).toBe(true);
    expect(fired.next.notified).toBe(true);

    // A later active step does not re-fire (one-shot per streak).
    const again = stepOrphanNotify(fired.next, true, 1_000 + ORPHAN_NOTIFY_DELAY_MS + 5_000);
    expect(again.fire).toBe(false);
    expect(again.next.notified).toBe(true);
  });

  it('resets when the condition clears so a later orphan re-notifies', () => {
    const first = stepOrphanNotify(INITIAL_ORPHAN_NOTIFY_STATE, true, 1_000);
    const fired = stepOrphanNotify(first.next, true, 1_000 + ORPHAN_NOTIFY_DELAY_MS);

    const cleared = stepOrphanNotify(fired.next, false, 2_000_000);
    expect(cleared.fire).toBe(false);
    expect(cleared.next).toEqual(INITIAL_ORPHAN_NOTIFY_STATE);

    // Fresh streak: clock restarts, and firing needs the full window again.
    const restart = stepOrphanNotify(cleared.next, true, 3_000_000);
    expect(restart.fire).toBe(false);
    expect(restart.next.since).toBe(3_000_000);
    const refire = stepOrphanNotify(restart.next, true, 3_000_000 + ORPHAN_NOTIFY_DELAY_MS);
    expect(refire.fire).toBe(true);
  });
});

describe('shouldNotifyDisk', () => {
  it('fires when nothing has fired yet', () => {
    expect(shouldNotifyDisk(null, 1_000)).toBe(true);
  });

  it('suppresses a repeat within the debounce window', () => {
    expect(shouldNotifyDisk(1_000, 1_000 + DISK_NOTIFY_DEBOUNCE_MS - 1)).toBe(false);
  });

  it('fires again once the debounce window has elapsed', () => {
    expect(shouldNotifyDisk(1_000, 1_000 + DISK_NOTIFY_DEBOUNCE_MS)).toBe(true);
  });
});
