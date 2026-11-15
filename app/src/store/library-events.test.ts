import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { startLibrary, useLibraryStore } from './library';

// Integration coverage for the storage-events wiring in startLibrary: a retention.swept frame drives a
// library refetch, a low-disk error frame lands in the banner state, and a socket reconnect reconciles
// with a fresh refetch. Drives a fake WebSocket + stubbed fetch under fake timers (Node env — no DOM).

const EMPTY_COUNTS = {
  ranked: 0,
  unranked: 0,
  turbo: 0,
  abilityDraft: 0,
  manual: 0,
  clips: 0,
  unsorted: 0,
};

const IDLE_STATUS = {
  gsi: { connected: false, lastFrameAgoMs: null },
  obs: { connected: false, sceneActive: false, recording: false },
  fsm: { state: 'IDLE', activeMatchId: null },
};

// Minimal controllable WebSocket: StatusSocket sets on{open,message,close,error} as properties, so the
// test drives lifecycle by invoking them. Every instance is recorded so the reconnect can grab the
// freshly-opened socket.
class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  constructor(public url: string) {
    FakeWebSocket.instances.push(this);
  }
  close(): void {
    this.onclose?.();
  }
  emitOpen(): void {
    this.onopen?.();
  }
  emitMessage(obj: unknown): void {
    this.onmessage?.({ data: JSON.stringify(obj) });
  }
}

let fetchMock: ReturnType<typeof vi.fn>;

function matchesCallCount(): number {
  return fetchMock.mock.calls.filter((c) => String(c[0]).includes('/matches')).length;
}

// Flush pending microtasks (fetch/allSettled resolutions) without advancing wall-clock timers.
async function flush(): Promise<void> {
  await vi.advanceTimersByTimeAsync(0);
}

// What the core holds, for the delete tests: each list is what its GET returns.
interface CoreLibrary {
  matches: { id: number }[];
  clips: { id: number; parentMatchId: number }[];
}

// Stubs fetch with a core holding `core`. A DELETE drops its row there first, as the core does once
// it gets its maintenance lock (even when the request has given up by then), and is then answered
// with whatever `answer` returns for its URL. Returns the URLs of the DELETEs sent, in order.
function serveLibrary(core: CoreLibrary, answer: (url: string) => Promise<unknown>): string[] {
  const deletes: string[] = [];
  fetchMock.mockImplementation((url: unknown, init?: { method?: string }) => {
    const u = String(url);
    if (init?.method === 'DELETE') {
      deletes.push(u);
      core.matches = core.matches.filter((m) => !u.endsWith(`/matches/${m.id}`));
      core.clips = core.clips.filter((c) => !u.endsWith(`/clips/${c.id}`));
      return answer(u);
    }
    const json = u.includes('/buckets/counts')
      ? EMPTY_COUNTS
      : u.includes('/status')
        ? IDLE_STATUS
        : u.includes('/clips')
          ? core.clips
          : core.matches;
    return Promise.resolve({
      ok: true,
      json: async () => json,
      text: async () => JSON.stringify(json),
    });
  });
  return deletes;
}

// The answers a DELETE can get: done, refused (a locked file, say), or given up on behind the lock.
const deleted = (): Promise<unknown> =>
  Promise.resolve({ ok: true, json: async () => ({ outcome: 'deleted' }) });
const refused = (): Promise<unknown> =>
  Promise.resolve({ ok: false, status: 500, statusText: 'Internal Server Error' });
const timedOut = (): Promise<unknown> =>
  Promise.reject(new DOMException('The operation timed out.', 'TimeoutError'));

const matchIds = (): number[] => useLibraryStore.getState().matches.map((m) => m.id);
const clipIds = (): number[] => useLibraryStore.getState().clips.map((c) => c.id);

beforeEach(() => {
  vi.useFakeTimers();
  FakeWebSocket.instances = [];
  useLibraryStore.setState({
    matches: [],
    clips: [],
    counts: EMPTY_COUNTS,
    status: null,
    loadState: 'idle',
    diskWarning: null,
  });
  fetchMock = vi.fn((url: unknown) => {
    const u = String(url);
    const json = u.includes('/buckets/counts')
      ? EMPTY_COUNTS
      : u.includes('/status')
        ? IDLE_STATUS
        : u.includes('/clips')
          ? []
          : []; // '/matches'
    return Promise.resolve({
      ok: true,
      json: async () => json,
      text: async () => JSON.stringify(json),
    });
  });
  vi.stubGlobal('fetch', fetchMock);
  vi.stubGlobal('WebSocket', FakeWebSocket);
  vi.stubGlobal('window', {});
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('startLibrary storage-events wiring', () => {
  it('refetches the library on a retention.swept frame', async () => {
    const teardown = startLibrary();
    await flush(); // initial load + status prime settle
    const ws = FakeWebSocket.instances[0];
    const before = matchesCallCount();

    ws.emitMessage({ type: 'retention.swept', payload: { freedBytes: 1000, deletedIds: [7] } });
    await vi.advanceTimersByTimeAsync(200); // scheduleReload's coalescing delay
    await flush();

    expect(matchesCallCount()).toBeGreaterThan(before);
    teardown();
  });

  it('lands a low-disk error frame in the banner state', async () => {
    const teardown = startLibrary();
    await flush();
    const ws = FakeWebSocket.instances[0];

    ws.emitMessage({
      type: 'error',
      payload: { scope: 'disk', freeBytes: 500, thresholdBytes: 1000, message: 'Low disk space' },
    });
    await flush();

    expect(useLibraryStore.getState().diskWarning).toEqual({
      freeBytes: 500,
      thresholdBytes: 1000,
      message: 'Low disk space',
    });
    teardown();
  });

  it('refetches the library on a match.deleted frame (a delete that outlived its request)', async () => {
    const teardown = startLibrary();
    await flush();
    const ws = FakeWebSocket.instances[0];
    const before = matchesCallCount();

    ws.emitMessage({ type: 'match.deleted', payload: { id: 7, outcome: 'deleted' } });
    await vi.advanceTimersByTimeAsync(200); // scheduleReload's coalescing delay
    await flush();

    expect(matchesCallCount()).toBeGreaterThan(before);
    teardown();
  });

  it('reconciles with the core when a recording delete times out', async () => {
    // The DELETE gives up behind a long archive pass, and the core finishes it once the lock frees.
    // Only the reconcile reload can show that 7 went.
    serveLibrary({ matches: [{ id: 7 }, { id: 8 }], clips: [] }, timedOut);
    const teardown = startLibrary();
    await flush();
    expect(matchIds()).toEqual([7, 8]);

    await expect(useLibraryStore.getState().deleteMatch(7)).rejects.toThrow();
    await flush();

    expect(matchIds()).toEqual([8]);
    teardown();
  });

  it('applies the reconcile reload of a bulk delete in which one delete failed', async () => {
    // 7's delete is refused (a locked file, say) though the core dropped the row anyway; 8's
    // succeeds. Only the reconcile reload can show that 7 went too.
    serveLibrary({ matches: [{ id: 7 }, { id: 8 }, { id: 9 }], clips: [] }, (url) =>
      url.endsWith('/matches/7') ? refused() : deleted(),
    );
    const teardown = startLibrary();
    await flush();
    expect(matchIds()).toEqual([7, 8, 9]);

    await useLibraryStore.getState().deleteMatches([7, 8]);
    await flush();

    expect(matchIds()).toEqual([9]);
    teardown();
  });

  it('stops a bulk delete at a delete that timed out, then reconciles', async () => {
    // 7's delete waited out its whole timeout behind the core's maintenance lock (the core finishes
    // it later). 8 and 9 would only wait as long behind it, so they aren't sent; the reconcile
    // reload shows what the core holds.
    const deletes = serveLibrary(
      { matches: [{ id: 7 }, { id: 8 }, { id: 9 }], clips: [] },
      (url) => (url.endsWith('/matches/7') ? timedOut() : deleted()),
    );
    const teardown = startLibrary();
    await flush();

    await useLibraryStore.getState().deleteMatches([7, 8, 9]);
    await flush();

    expect(deletes).toHaveLength(1);
    expect(matchIds()).toEqual([8, 9]);
    teardown();
  });

  it('goes on past a refused clip delete in a bulk delete, then applies its reconcile reload', async () => {
    // Clip 7's delete is refused though the core dropped the clip anyway; 8 is still sent and goes.
    // Only the reconcile reload, started after the bulk delete's own invalidation, shows 7 went too.
    const clips = [7, 8, 9].map((id) => ({ id, parentMatchId: 1 }));
    serveLibrary({ matches: [{ id: 1 }], clips }, (url) =>
      url.endsWith('/clips/7') ? refused() : deleted(),
    );
    const teardown = startLibrary();
    await flush();
    expect(clipIds()).toEqual([7, 8, 9]);

    await useLibraryStore.getState().deleteClips([7, 8]);
    await flush();

    expect(clipIds()).toEqual([9]);
    teardown();
  });

  it('reconciles with a refetch when the socket reconnects', async () => {
    const teardown = startLibrary();
    await flush();
    // Establish the first connection (its own reconcile) so the baseline already accounts for it.
    FakeWebSocket.instances[0].emitOpen();
    await flush();
    const before = matchesCallCount();

    // Drop the socket, let the backoff elapse so it reopens, then complete the reconnect handshake.
    FakeWebSocket.instances[0].close();
    await vi.advanceTimersByTimeAsync(500); // exponential-backoff initial delay
    const reconnected = FakeWebSocket.instances[FakeWebSocket.instances.length - 1];
    reconnected.emitOpen();
    await flush();

    expect(matchesCallCount()).toBeGreaterThan(before);
    teardown();
  });
});
