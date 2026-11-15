import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The recording and clip delete requests. A delete waits for the core's storage-maintenance lock,
// which an archive pass holds through all its cross-drive copies, so it gets 10 minutes where every
// other request gets 5 or 10 seconds, and the deletes go out one at a time so that waiting ones hold
// one connection to the core between them. Runs in Node: fetch and AbortSignal.timeout are stubbed.

// The client's delete queue is module state: every test gets a fresh copy of the module.
let client: typeof import('./client');
let fetchMock: ReturnType<typeof vi.fn>;
// What each AbortSignal.timeout call asked for, and the signal it handed back.
let timeouts: number[];
let signals: AbortSignal[];

const done = (): Promise<unknown> =>
  Promise.resolve({ ok: true, json: async () => ({ outcome: 'deleted' }) });

// A response the test hands back by hand: a delete waiting on the core's maintenance lock.
function heldResponse(): { promise: Promise<unknown>; release: () => void } {
  let release!: () => void;
  const promise = new Promise<unknown>((resolve) => {
    release = () => resolve({ ok: true, json: async () => ({ outcome: 'deleted' }) });
  });
  return { promise, release };
}

// Lets every settled promise's callbacks run.
const settle = (): Promise<void> => new Promise((r) => setTimeout(r, 0));

const sentDeletes = (): string[] =>
  fetchMock.mock.calls
    .filter((c) => (c[1] as RequestInit | undefined)?.method === 'DELETE')
    .map((c) => String(c[0]));

beforeEach(async () => {
  vi.resetModules();
  client = await import('./client');
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
  it('give a recording delete and a clip delete 10 minutes to finish', async () => {
    await client.deleteMatch(7);
    await client.deleteClip(8);

    expect(timeouts).toEqual([10 * 60_000, 10 * 60_000]);
    // Each request carries the signal made for it.
    expect(fetchMock).toHaveBeenCalledTimes(2);
    fetchMock.mock.calls.forEach((call, i) => {
      expect((call[1] as RequestInit).signal).toBe(signals[i]);
    });
  });

  it('go out one at a time, each timed from when it is sent', async () => {
    const held = heldResponse();
    fetchMock.mockImplementationOnce(() => held.promise);

    const match = client.deleteMatch(7);
    const clip = client.deleteClip(8);
    await settle();
    // The clip delete waits its turn without a clock running on it.
    expect(sentDeletes()).toEqual(['http://127.0.0.1:3224/matches/7']);
    expect(timeouts).toHaveLength(1);

    held.release();
    await match;
    await clip;
    expect(sentDeletes()).toEqual([
      'http://127.0.0.1:3224/matches/7',
      'http://127.0.0.1:3224/clips/8',
    ]);
    expect(timeouts).toHaveLength(2);
  });

  it('join a second delete of a row whose delete is still waiting instead of sending another', async () => {
    const held = heldResponse();
    fetchMock.mockImplementationOnce(() => held.promise);

    const first = client.deleteMatch(7);
    await settle();
    const again = client.deleteMatch(7);
    expect(again).toBe(first);

    held.release();
    await expect(again).resolves.toBe('deleted');
    expect(sentDeletes()).toEqual(['http://127.0.0.1:3224/matches/7']);
  });

  it('hold back no other request while one waits', async () => {
    fetchMock.mockImplementationOnce(() => heldResponse().promise); // never released

    void client.deleteMatch(7);
    await settle();
    void client.fetchMatches();
    await settle();

    expect(fetchMock.mock.calls.map((c) => String(c[0]))).toEqual([
      'http://127.0.0.1:3224/matches/7',
      'http://127.0.0.1:3224/matches',
    ]);
  });

  it("fail with the core's reason when it gives one, for the delete-failure banner", async () => {
    const refusal = (status: number, statusText: string, body: string) => () =>
      Promise.resolve({ ok: false, status, statusText, text: async () => body });
    fetchMock
      .mockImplementationOnce(refusal(404, 'Not Found', JSON.stringify({ message: 'No match 7' })))
      .mockImplementationOnce(refusal(404, 'Not Found', JSON.stringify({ message: 'No clip 8' })))
      .mockImplementationOnce(refusal(500, 'Internal Server Error', ''));

    await expect(client.deleteMatch(7)).rejects.toThrow('No match 7');
    await expect(client.deleteClip(8)).rejects.toThrow('No clip 8');
    await expect(client.deleteClip(9)).rejects.toThrow(
      'DELETE /clips/9 failed: 500 Internal Server Error',
    );
  });
});
