import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { deleteClip, deleteMatch, fetchMatches } from './client';

// The recording and clip delete requests. The core answers a delete within about half a minute: it
// waits that long for its storage-maintenance lock, which an archive pass holds through all its
// cross-drive copies, and then queues the delete and says so. So a delete gets 90 seconds where every
// other request gets 5 or 10, and each goes straight out: one the core is still waiting on holds back
// nothing else. Runs in Node: fetch and AbortSignal.timeout are stubbed.

let fetchMock: ReturnType<typeof vi.fn>;
// What each AbortSignal.timeout call asked for, and the signal it handed back.
let timeouts: number[];
let signals: AbortSignal[];

const done = (): Promise<unknown> =>
  Promise.resolve({ ok: true, status: 200, json: async () => ({ outcome: 'deleted' }) });

// What the core answers a delete it queued behind its maintenance lock.
const queued = (): Promise<unknown> =>
  Promise.resolve({ ok: true, status: 202, json: async () => ({ outcome: 'queued' }) });

// A response the test hands back by hand: a delete the core is still waiting on.
function heldResponse(): { promise: Promise<unknown>; release: () => void } {
  let release!: () => void;
  const promise = new Promise<unknown>((resolve) => {
    release = () => resolve({ ok: true, status: 200, json: async () => ({ outcome: 'deleted' }) });
  });
  return { promise, release };
}

// Lets every settled promise's callbacks run.
const settle = (): Promise<void> => new Promise((r) => setTimeout(r, 0));

const sentDeletes = (): string[] =>
  fetchMock.mock.calls
    .filter((c) => (c[1] as RequestInit | undefined)?.method === 'DELETE')
    .map((c) => String(c[0]));

beforeEach(() => {
  vi.stubGlobal('window', {});
  fetchMock = vi.fn(done);
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
  it("give a recording delete and a clip delete 90 seconds, well past the core's half-minute wait", async () => {
    await deleteMatch(7);
    await deleteClip(8);

    expect(timeouts).toEqual([90_000, 90_000]);
    // Each request carries the signal made for it.
    expect(fetchMock).toHaveBeenCalledTimes(2);
    fetchMock.mock.calls.forEach((call, i) => {
      expect((call[1] as RequestInit).signal).toBe(signals[i]);
    });
  });

  it('go out at once, so one the core is still waiting on holds back no other delete', async () => {
    const held = heldResponse();
    fetchMock.mockImplementationOnce(() => held.promise);

    const match = deleteMatch(7);
    const clip = deleteClip(8);
    await settle();

    expect(sentDeletes()).toEqual([
      'http://127.0.0.1:3224/matches/7',
      'http://127.0.0.1:3224/clips/8',
    ]);
    await expect(clip).resolves.toBe('deleted');
    held.release();
    await expect(match).resolves.toBe('deleted');
  });

  it('hold back no other request while one waits', async () => {
    fetchMock.mockImplementationOnce(() => heldResponse().promise); // never released

    void deleteMatch(7);
    await settle();
    void fetchMatches();
    await settle();

    expect(fetchMock.mock.calls.map((c) => String(c[0]))).toEqual([
      'http://127.0.0.1:3224/matches/7',
      'http://127.0.0.1:3224/matches',
    ]);
  });

  it('say so when the core queued the delete behind its maintenance lock', async () => {
    fetchMock.mockImplementation(queued);

    await expect(deleteMatch(7)).resolves.toBe('queued');
    await expect(deleteClip(8)).resolves.toBe('queued');
  });

  it('say which branch a done recording delete took, or nothing when the core does not', async () => {
    const answer = (status: number, body: unknown) => () =>
      Promise.resolve({ ok: true, status, json: async () => body });
    fetchMock
      .mockImplementationOnce(answer(200, { outcome: 'stubbed' }))
      .mockImplementationOnce(answer(200, {}))
      .mockImplementationOnce(answer(204, null));

    await expect(deleteMatch(7)).resolves.toBe('stubbed');
    await expect(deleteMatch(8)).resolves.toBeNull();
    // A clip delete that is done answers 204.
    await expect(deleteClip(9)).resolves.toBe('deleted');
  });

  it("fail with the core's reason when it gives one, for the delete-failure banner", async () => {
    const refusal = (status: number, statusText: string, body: string) => () =>
      Promise.resolve({ ok: false, status, statusText, text: async () => body });
    fetchMock
      .mockImplementationOnce(refusal(404, 'Not Found', JSON.stringify({ message: 'No match 7' })))
      .mockImplementationOnce(refusal(404, 'Not Found', JSON.stringify({ message: 'No clip 8' })))
      .mockImplementationOnce(refusal(500, 'Internal Server Error', ''));

    await expect(deleteMatch(7)).rejects.toThrow('No match 7');
    await expect(deleteClip(8)).rejects.toThrow('No clip 8');
    await expect(deleteClip(9)).rejects.toThrow(
      'DELETE /clips/9 failed: 500 Internal Server Error',
    );
  });
});
