import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { deleteClip, deleteMatch } from './client';

// The recording and clip delete requests. A delete waits for the core's storage-maintenance lock,
// which an archive pass holds through all its cross-drive copies, so it gets 10 minutes where every
// other request gets 5 or 10 seconds. Runs in Node: fetch and AbortSignal.timeout are stubbed.

let fetchMock: ReturnType<typeof vi.fn>;
// What each AbortSignal.timeout call asked for, and the signal it handed back.
let timeouts: number[];
let signals: AbortSignal[];

beforeEach(() => {
  vi.stubGlobal('window', {});
  fetchMock = vi.fn(() =>
    Promise.resolve({ ok: true, json: async () => ({ outcome: 'deleted' }) }),
  );
  vi.stubGlobal('fetch', fetchMock);
  timeouts = [];
  signals = [];
  // A signal that never fires: these tests check how long a delete is given, not that it gives up.
  vi.spyOn(AbortSignal, 'timeout').mockImplementation((ms) => {
    const signal = new AbortController().signal;
    timeouts.push(ms);
    signals.push(signal);
    return signal;
  });
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('delete requests', () => {
  it('give a recording delete and a clip delete 10 minutes to finish', async () => {
    await deleteMatch(7);
    await deleteClip(8);

    expect(timeouts).toEqual([10 * 60_000, 10 * 60_000]);
    // Each request carries the signal made for it.
    expect(fetchMock).toHaveBeenCalledTimes(2);
    fetchMock.mock.calls.forEach((call, i) => {
      expect((call[1] as RequestInit).signal).toBe(signals[i]);
    });
  });
});
