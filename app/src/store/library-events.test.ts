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

// The answer to a DELETE the core queued behind its maintenance lock: it keeps the row until it does
// the delete, which a test plays by dropping the row from its CoreLibrary and sending the frame.
const QUEUED = Symbol('queued');

// Stubs fetch with a core holding `core`. A DELETE drops its row there first, as the core does once
// it gets its maintenance lock (even when the request has given up by then), and is then answered
// with whatever `answer` returns for its URL; one answered QUEUED drops nothing yet and gets the
// core's 202. Returns the URLs of the DELETEs sent, in order.
function serveLibrary(
  core: CoreLibrary,
  answer: (url: string) => Promise<unknown> | typeof QUEUED,
): string[] {
  const deletes: string[] = [];
  fetchMock.mockImplementation((url: unknown, init?: { method?: string }) => {
    const u = String(url);
    if (init?.method === 'DELETE') {
      deletes.push(u);
      const answered = answer(u);
      if (answered === QUEUED) {
        return Promise.resolve({
          ok: true,
          status: 202,
          json: async () => ({ outcome: 'queued' }),
        });
      }
      core.matches = core.matches.filter((m) => !u.endsWith(`/matches/${m.id}`));
      core.clips = core.clips.filter((c) => !u.endsWith(`/clips/${c.id}`));
      return answered;
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
  Promise.resolve({ ok: true, status: 200, json: async () => ({ outcome: 'deleted' }) });
const refused = (): Promise<unknown> =>
  Promise.resolve({ ok: false, status: 500, statusText: 'Internal Server Error' });
const timedOut = (): Promise<unknown> =>
  Promise.reject(new DOMException('The operation timed out.', 'TimeoutError'));
// The core has no such row: it went already.
const notFound = (message: string) => (): Promise<unknown> =>
  Promise.resolve({
    ok: false,
    status: 404,
    statusText: 'Not Found',
    text: async () => JSON.stringify({ message }),
  });

// A DELETE the core is still waiting on (its half-minute wait for the maintenance lock), answered when
// the test releases it.
function heldDelete(): { answer: Promise<unknown>; release: () => void } {
  let release!: () => void;
  const answer = new Promise<void>((resolve) => (release = resolve)).then(deleted);
  return { answer, release };
}

const matchIds = (): number[] => useLibraryStore.getState().matches.map((m) => m.id);
const clipIds = (): number[] => useLibraryStore.getState().clips.map((c) => c.id);
const byId = (a: number, b: number): number => a - b;
const deletingMatches = (): number[] => [...useLibraryStore.getState().deletingMatchIds].sort(byId);
const deletingClips = (): number[] => [...useLibraryStore.getState().deletingClipIds].sort(byId);
const queuedMatches = (): number[] => [...useLibraryStore.getState().queuedMatchIds].sort(byId);
const queuedClips = (): number[] => [...useLibraryStore.getState().queuedClipIds].sort(byId);

beforeEach(() => {
  vi.useFakeTimers();
  FakeWebSocket.instances = [];
  useLibraryStore.setState({
    matches: [],
    clips: [],
    counts: EMPTY_COUNTS,
    status: null,
    loadState: 'idle',
    deletingMatchIds: new Set<number>(),
    deletingClipIds: new Set<number>(),
    queuedMatchIds: new Set<number>(),
    queuedClipIds: new Set<number>(),
    deleteError: null,
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

  it('reloads in place on a library frame, unlike the first load', async () => {
    const teardown = startLibrary();
    // The first load has nothing to show yet, so it shows the loading state.
    expect(useLibraryStore.getState().loadState).toBe('loading');
    await flush();
    expect(useLibraryStore.getState().loadState).toBe('ready');
    // Hold the frame's reload in flight to see what the table shows meanwhile.
    let release!: () => void;
    const held = new Promise<void>((resolve) => (release = resolve));
    const serve = fetchMock.getMockImplementation() as (url: unknown) => Promise<unknown>;
    fetchMock.mockImplementation(async (url: unknown) => {
      await held;
      return serve(url);
    });
    const before = matchesCallCount();

    FakeWebSocket.instances[0].emitMessage({
      type: 'match.deleted',
      payload: { id: 7, outcome: 'deleted' },
    });
    await vi.advanceTimersByTimeAsync(200); // scheduleReload's coalescing delay

    // The reload is in flight and the rows stay up: the loading state would hide them all.
    expect(matchesCallCount()).toBeGreaterThan(before);
    expect(useLibraryStore.getState().loadState).toBe('ready');
    release();
    await flush();
    expect(useLibraryStore.getState().loadState).toBe('ready');
    teardown();
  });

  it('still shows the error state when a frame reload cannot reach the core', async () => {
    const teardown = startLibrary();
    await flush();
    fetchMock.mockImplementation(() => Promise.reject(new TypeError('Failed to fetch')));

    FakeWebSocket.instances[0].emitMessage({
      type: 'retention.swept',
      payload: { deletedIds: [] },
    });
    await vi.advanceTimersByTimeAsync(200);
    await flush();

    expect(useLibraryStore.getState().loadState).toBe('error');
    teardown();
  });

  it('reconciles with the core when a recording delete times out', async () => {
    // The core didn't answer in time, but did the delete. Only the reconcile reload can show that 7
    // went.
    serveLibrary({ matches: [{ id: 7 }, { id: 8 }], clips: [] }, timedOut);
    const teardown = startLibrary();
    await flush();
    expect(matchIds()).toEqual([7, 8]);

    await expect(useLibraryStore.getState().deleteMatch(7)).rejects.toThrow();
    await flush();

    expect(matchIds()).toEqual([8]);
    teardown();
  });

  it('reconciles with the core when a single clip delete fails or times out', async () => {
    // The core dropped clips 5 and 6 although 5's answer was a refusal and 6's never came (its
    // clip.deleted frame missed, say, with the socket down). Only the reconcile reload can show that
    // they went.
    const clips = [5, 6, 7].map((id) => ({ id, parentMatchId: 1 }));
    serveLibrary({ matches: [{ id: 1 }], clips }, (url) =>
      url.endsWith('/clips/5') ? refused() : timedOut(),
    );
    const teardown = startLibrary();
    await flush();
    expect(clipIds()).toEqual([5, 6, 7]);

    await expect(useLibraryStore.getState().deleteClip(5)).rejects.toThrow();
    await flush();
    expect(clipIds()).toEqual([6, 7]);

    await expect(useLibraryStore.getState().deleteClip(6)).rejects.toThrow();
    await flush();
    expect(clipIds()).toEqual([7]);
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

  it('sends every row of a bulk delete past one that timed out, then reconciles', async () => {
    // 7's delete waited out its whole timeout behind the core's maintenance lock, and the core
    // finishes it once the lock frees. 8 and 9 are still sent, and the core finishes them then too;
    // only the reconcile reload shows that 7 went as well.
    const deletes = serveLibrary(
      { matches: [{ id: 7 }, { id: 8 }, { id: 9 }], clips: [] },
      (url) => (url.endsWith('/matches/7') ? timedOut() : deleted()),
    );
    const teardown = startLibrary();
    await flush();

    await useLibraryStore.getState().deleteMatches([7, 8, 9]);
    await flush();

    expect(deletes).toHaveLength(3);
    expect(matchIds()).toEqual([]);
    teardown();
  });

  it('sends every clip of a bulk delete past one that timed out, then reconciles', async () => {
    const clips = [7, 8, 9].map((id) => ({ id, parentMatchId: 1 }));
    const deletes = serveLibrary({ matches: [{ id: 1 }], clips }, (url) =>
      url.endsWith('/clips/7') ? timedOut() : deleted(),
    );
    const teardown = startLibrary();
    await flush();

    await useLibraryStore.getState().deleteClips([7, 8, 9]);
    await flush();

    expect(deletes).toHaveLength(3);
    expect(clipIds()).toEqual([]);
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

  it('marks a recording as deleting while its delete request is in flight', async () => {
    const held = heldDelete();
    const deletes = serveLibrary({ matches: [{ id: 7 }, { id: 8 }], clips: [] }, () => held.answer);
    const teardown = startLibrary();
    await flush();

    const run = useLibraryStore.getState().deleteMatch(7);
    await flush();
    expect(deletingMatches()).toEqual([7]);
    expect(matchIds()).toEqual([7, 8]);

    held.release();
    await run;
    expect(deletes).toEqual(['http://127.0.0.1:3224/matches/7']);
    expect(deletingMatches()).toEqual([]);
    expect(matchIds()).toEqual([8]);
    teardown();
  });

  it('keeps a recording the core queued marked until its match.deleted frame drops it', async () => {
    // The core waited out its half minute for the maintenance lock and queued the delete: the row is
    // still there, and nothing but the frame will say when it goes.
    const core = { matches: [{ id: 7 }, { id: 8 }], clips: [] };
    serveLibrary(core, () => QUEUED);
    const teardown = startLibrary();
    await flush();

    await useLibraryStore.getState().deleteMatch(7);
    expect(deletingMatches()).toEqual([]);
    expect(queuedMatches()).toEqual([7]);
    expect(matchIds()).toEqual([7, 8]);
    expect(useLibraryStore.getState().deleteError).toBeNull();

    // The lock frees and the core does the delete.
    core.matches = [{ id: 8 }];
    FakeWebSocket.instances[0].emitMessage({
      type: 'match.deleted',
      payload: { id: 7, outcome: 'deleted' },
    });
    // Gone at once, before the frame's reload, so it never shows as an ordinary row meanwhile.
    expect(matchIds()).toEqual([8]);
    expect(queuedMatches()).toEqual([]);
    await vi.advanceTimersByTimeAsync(200);
    await flush();
    expect(matchIds()).toEqual([8]);
    teardown();
  });

  it('keeps the stub of a queued recording with clips once its frame says it was stubbed', async () => {
    const core = {
      matches: [{ id: 7, videoPath: 'C:/vods/7.mp4' }],
      clips: [{ id: 5, parentMatchId: 7 }],
    };
    serveLibrary(core, () => QUEUED);
    const teardown = startLibrary();
    await flush();

    await useLibraryStore.getState().deleteMatch(7);
    FakeWebSocket.instances[0].emitMessage({
      type: 'match.deleted',
      payload: { id: 7, outcome: 'stubbed' },
    });

    expect(useLibraryStore.getState().matches).toEqual([
      expect.objectContaining({ id: 7, videoPath: null }),
    ]);
    expect(clipIds()).toEqual([5]);
    expect(queuedMatches()).toEqual([]);
    teardown();
  });

  it('keeps a clip the core queued marked until its clip.deleted frame drops it', async () => {
    const core = { matches: [{ id: 1 }], clips: [5, 6].map((id) => ({ id, parentMatchId: 1 })) };
    serveLibrary(core, () => QUEUED);
    const teardown = startLibrary();
    await flush();

    await expect(useLibraryStore.getState().deleteClip(5)).resolves.toBe('queued');
    expect(deletingClips()).toEqual([]);
    expect(queuedClips()).toEqual([5]);
    expect(clipIds()).toEqual([5, 6]);

    core.clips = [{ id: 6, parentMatchId: 1 }];
    FakeWebSocket.instances[0].emitMessage({
      type: 'clip.deleted',
      payload: { clipId: 5, parentMatchId: 1 },
    });
    expect(clipIds()).toEqual([6]);
    expect(queuedClips()).toEqual([]);
    teardown();
  });

  it('keeps a reload that read the list before a delete frame from bringing the row back', async () => {
    // Another frame's reload read the list while the core still had recording 7, then clip 5, and is
    // still out when the delete's frame comes.
    const core = { matches: [{ id: 7 }, { id: 8 }], clips: [{ id: 5, parentMatchId: 8 }] };
    serveLibrary(core, () => QUEUED);
    const teardown = startLibrary();
    await flush();
    await useLibraryStore.getState().deleteMatch(7);
    await useLibraryStore.getState().deleteClip(5);
    const ws = FakeWebSocket.instances[0];
    // From here each GET reads the core's list when it is sent, and is answered once released.
    const serve = fetchMock.getMockImplementation() as (
      url: unknown,
      init?: { method?: string },
    ) => Promise<unknown>;
    let held: Promise<void> = Promise.resolve();
    fetchMock.mockImplementation(async (url: unknown, init?: { method?: string }) => {
      const answer = serve(url, init);
      await held;
      return answer;
    });
    // Sends a reload that reads the core as it is now, does `done` to the core and sends its frame,
    // then lets the reload land.
    const staleReloadThen = async (done: () => void, frame: unknown): Promise<void> => {
      let release!: () => void;
      held = new Promise<void>((resolve) => (release = resolve));
      ws.emitMessage({ type: 'match.enriched', payload: { id: 8 } });
      await vi.advanceTimersByTimeAsync(200); // its reload goes out
      done();
      ws.emitMessage(frame);
      release();
      await flush();
    };

    await staleReloadThen(() => (core.matches = [{ id: 8 }]), {
      type: 'match.deleted',
      payload: { id: 7, outcome: 'deleted' },
    });
    // The reload read 7, but was dropped: 7 doesn't come back as an ordinary row.
    expect(matchIds()).toEqual([8]);
    expect(clipIds()).toEqual([5]);

    await staleReloadThen(() => (core.clips = []), {
      type: 'clip.deleted',
      payload: { clipId: 5, parentMatchId: 8 },
    });
    expect(clipIds()).toEqual([]);
    expect(matchIds()).toEqual([8]);
    teardown();
  });

  it('keeps the first load when a delete frame comes before it lands', async () => {
    // Dropping the first load would leave the library empty until the frame's reload landed.
    const core = { matches: [{ id: 7 }, { id: 8 }], clips: [] };
    serveLibrary(core, () => QUEUED);
    let release!: () => void;
    const held = new Promise<void>((resolve) => (release = resolve));
    const serve = fetchMock.getMockImplementation() as (url: unknown) => Promise<unknown>;
    fetchMock.mockImplementation(async (url: unknown) => {
      const answer = serve(url);
      await held;
      return answer;
    });
    const teardown = startLibrary();
    await flush();
    expect(useLibraryStore.getState().loadState).toBe('loading');

    core.matches = [{ id: 8 }];
    FakeWebSocket.instances[0].emitMessage({
      type: 'match.deleted',
      payload: { id: 7, outcome: 'deleted' },
    });
    release();
    await flush();

    // The first load shows what it read (7 too, for now), and the frame's reload then drops 7.
    expect(useLibraryStore.getState().loadState).toBe('ready');
    expect(matchIds()).toEqual([7, 8]);
    await vi.advanceTimersByTimeAsync(200);
    await flush();
    expect(matchIds()).toEqual([8]);
    teardown();
  });

  it('marks the rows of a bulk delete the core queued, and drops the ones it did', async () => {
    serveLibrary({ matches: [{ id: 7 }, { id: 8 }, { id: 9 }], clips: [] }, (url) =>
      url.endsWith('/matches/7') ? QUEUED : deleted(),
    );
    const teardown = startLibrary();
    await flush();

    await useLibraryStore.getState().deleteMatches([7, 8]);

    expect(matchIds()).toEqual([7, 9]);
    expect(deletingMatches()).toEqual([]);
    expect(queuedMatches()).toEqual([7]);
    expect(useLibraryStore.getState().deleteError).toBeNull();
    teardown();
  });

  it('does not mark a queued row again once the core said its delete is over', async () => {
    // The core queued 8's delete and, the lock free again, did it at once: its frame came before the
    // bulk run that asked for it settled, since 7's answer was still out. Clip 5 likewise.
    const held = heldDelete();
    const core = {
      matches: [{ id: 7 }, { id: 8, videoPath: 'C:/vods/8.mp4' }],
      clips: [5, 6].map((id) => ({ id, parentMatchId: 8 })),
    };
    serveLibrary(core, (url) =>
      url.endsWith('/matches/7') || url.endsWith('/clips/6') ? held.answer : QUEUED,
    );
    const teardown = startLibrary();
    await flush();
    const ws = FakeWebSocket.instances[0];

    const recordings = useLibraryStore.getState().deleteMatches([7, 8]);
    const clips = useLibraryStore.getState().deleteClips([5, 6]);
    await flush();
    ws.emitMessage({ type: 'match.deleted', payload: { id: 8, outcome: 'stubbed' } });
    core.clips = [{ id: 6, parentMatchId: 8 }];
    ws.emitMessage({ type: 'clip.deleted', payload: { clipId: 5, parentMatchId: 8 } });
    held.release();
    await recordings;
    await clips;

    expect(queuedMatches()).toEqual([]);
    expect(queuedClips()).toEqual([]);
    expect(useLibraryStore.getState().matches).toEqual([
      expect.objectContaining({ id: 8, videoPath: null }),
    ]);
    teardown();
  });

  it('does not mark a queued row again once the core said its delete failed', async () => {
    // As above, but the core could not do 8's queued delete, nor clip 5's: their delete.failed frames
    // came while 7's and clip 6's answers were still out.
    const held = heldDelete();
    const core = {
      matches: [{ id: 7 }, { id: 8 }],
      clips: [5, 6].map((id) => ({ id, parentMatchId: 8 })),
    };
    serveLibrary(core, (url) =>
      url.endsWith('/matches/7') || url.endsWith('/clips/6') ? held.answer : QUEUED,
    );
    const teardown = startLibrary();
    await flush();
    const ws = FakeWebSocket.instances[0];

    const recordings = useLibraryStore.getState().deleteMatches([7, 8]);
    const clips = useLibraryStore.getState().deleteClips([5, 6]);
    await flush();
    ws.emitMessage({
      type: 'delete.failed',
      payload: { kind: 'match', id: 8, message: 'database is locked' },
    });
    ws.emitMessage({
      type: 'delete.failed',
      payload: { kind: 'clip', id: 5, message: 'database is locked' },
    });
    held.release();
    await recordings;
    await clips;

    // Not "Deleting…" next to a banner that says the delete failed.
    expect(queuedMatches()).toEqual([]);
    expect(queuedClips()).toEqual([]);
    expect(useLibraryStore.getState().deleteError).toBe(
      'The clip was not deleted: database is locked',
    );
    teardown();
  });

  it('keeps a clip delete and a recording delete with the same id apart', async () => {
    // Recording 7 and clip 7 (of recording 1) both exist: each delete marks, and is done, on its own.
    const held = heldDelete();
    const core = {
      matches: [{ id: 1 }, { id: 7 }],
      clips: [{ id: 7, parentMatchId: 1 }],
    };
    serveLibrary(core, (url) => (url.endsWith('/matches/7') ? held.answer : QUEUED));
    const teardown = startLibrary();
    await flush();

    const recording = useLibraryStore.getState().deleteMatch(7);
    await expect(useLibraryStore.getState().deleteClip(7)).resolves.toBe('queued');
    expect(deletingMatches()).toEqual([7]);
    expect(deletingClips()).toEqual([]);
    expect(queuedMatches()).toEqual([]);
    expect(queuedClips()).toEqual([7]);

    // The recording's delete is done: only the recording goes, and the clip stays queued.
    held.release();
    await recording;
    expect(matchIds()).toEqual([1]);
    expect(clipIds()).toEqual([7]);
    expect(queuedClips()).toEqual([7]);

    // The clip's frame drops the clip alone.
    core.clips = [];
    FakeWebSocket.instances[0].emitMessage({
      type: 'clip.deleted',
      payload: { clipId: 7, parentMatchId: 1 },
    });
    expect(clipIds()).toEqual([]);
    expect(matchIds()).toEqual([1]);
    expect(queuedClips()).toEqual([]);
    teardown();
  });

  it('marks every row of a bulk delete as deleting until the whole run is done', async () => {
    const held = heldDelete();
    serveLibrary({ matches: [{ id: 7 }, { id: 8 }, { id: 9 }], clips: [] }, (url) =>
      url.endsWith('/matches/8') ? held.answer : deleted(),
    );
    const teardown = startLibrary();
    await flush();

    const run = useLibraryStore.getState().deleteMatches([7, 8]);
    await flush();
    // 7 is done and 8 still waits: both stay marked until the run lands in one update.
    expect(deletingMatches()).toEqual([7, 8]);
    expect(matchIds()).toEqual([7, 8, 9]);

    held.release();
    await run;
    expect(deletingMatches()).toEqual([]);
    expect(matchIds()).toEqual([9]);
    teardown();
  });

  it('sends every row of a bulk delete at once, so none is left in a window that closes', async () => {
    // The first delete waits its half minute for the core's maintenance lock. Had the rest waited in
    // this window behind it, closing the window to the tray then would have lost them: the core never
    // heard of them.
    const held = heldDelete();
    const deletes = serveLibrary(
      {
        matches: [{ id: 7 }, { id: 8 }, { id: 9 }],
        clips: [5, 6].map((id) => ({ id, parentMatchId: 7 })),
      },
      (url) => (url.endsWith('/matches/7') || url.endsWith('/clips/5') ? held.answer : deleted()),
    );
    const teardown = startLibrary();
    await flush();

    const recordings = useLibraryStore.getState().deleteMatches([7, 8, 9]);
    const clips = useLibraryStore.getState().deleteClips([5, 6]);
    await flush();
    expect(deletes).toEqual([
      'http://127.0.0.1:3224/matches/7',
      'http://127.0.0.1:3224/matches/8',
      'http://127.0.0.1:3224/matches/9',
      'http://127.0.0.1:3224/clips/5',
      'http://127.0.0.1:3224/clips/6',
    ]);

    held.release();
    await recordings;
    await clips;
    teardown();
  });

  it('unmarks a delete that was refused or timed out', async () => {
    serveLibrary({ matches: [{ id: 7 }], clips: [{ id: 5, parentMatchId: 7 }] }, (url) =>
      url.endsWith('/matches/7') ? refused() : timedOut(),
    );
    const teardown = startLibrary();
    await flush();

    await expect(useLibraryStore.getState().deleteMatch(7)).rejects.toThrow();
    await expect(useLibraryStore.getState().deleteClip(5)).rejects.toThrow();

    expect(deletingMatches()).toEqual([]);
    expect(deletingClips()).toEqual([]);
    teardown();
  });

  it('marks what the core says it is deleting, in a window opened after the deletes were asked for', async () => {
    // The window was closed to the tray while the core waited on its maintenance lock with these
    // queued: the new one learns of them from GET /status, then from each status frame.
    const statusWith = (matchIds: number[], clipIds: number[]) => ({
      ...IDLE_STATUS,
      deletes: { matchIds, clipIds },
    });
    let status = statusWith([7], [5]);
    const serve = fetchMock.getMockImplementation() as (url: unknown) => Promise<unknown>;
    fetchMock.mockImplementation((url: unknown) =>
      String(url).includes('/status')
        ? Promise.resolve({ ok: true, json: async () => status })
        : serve(url),
    );
    const teardown = startLibrary();
    await flush();
    expect(queuedMatches()).toEqual([7]);
    expect(queuedClips()).toEqual([5]);

    const ws = FakeWebSocket.instances[0];
    status = statusWith([7, 8], []);
    ws.emitMessage({ type: 'status', payload: status });
    expect(queuedMatches()).toEqual([7, 8]);
    expect(queuedClips()).toEqual([]);

    // A status that changes nothing keeps the very same sets: no re-render every five seconds.
    const before = useLibraryStore.getState().queuedMatchIds;
    ws.emitMessage({ type: 'status', payload: status });
    expect(useLibraryStore.getState().queuedMatchIds).toBe(before);

    // A dropped socket says nothing about them: the core most likely still has them.
    ws.close();
    expect(useLibraryStore.getState().status).toBeNull();
    expect(queuedMatches()).toEqual([7, 8]);
    teardown();
  });

  it('keeps a delete that timed out marked while the core still lists it', async () => {
    // The core didn't answer in time but has the delete still (its status says so), so the row must
    // not look like an ordinary one that can be deleted again.
    serveLibrary({ matches: [{ id: 7 }, { id: 8 }], clips: [] }, timedOut);
    const teardown = startLibrary();
    await flush();
    const ws = FakeWebSocket.instances[0];
    ws.emitMessage({
      type: 'status',
      payload: { ...IDLE_STATUS, deletes: { matchIds: [7], clipIds: [] } },
    });

    await expect(useLibraryStore.getState().deleteMatch(7)).rejects.toThrow();
    expect(deletingMatches()).toEqual([]);
    expect(queuedMatches()).toEqual([7]);
    expect(useLibraryStore.getState().deleteError).toBeNull();

    // The core lets go of it (it finished it and its frame was missed, or it was restarted).
    ws.emitMessage({
      type: 'status',
      payload: { ...IDLE_STATUS, deletes: { matchIds: [], clipIds: [] } },
    });
    expect(queuedMatches()).toEqual([]);
    teardown();
  });

  it('says why the core could not do a delete it queued, and unmarks the row', async () => {
    const core = { matches: [{ id: 7 }], clips: [{ id: 5, parentMatchId: 7 }] };
    serveLibrary(core, () => QUEUED);
    const teardown = startLibrary();
    await flush();
    await useLibraryStore.getState().deleteMatch(7);
    await useLibraryStore.getState().deleteClip(5);
    expect(queuedMatches()).toEqual([7]);
    expect(queuedClips()).toEqual([5]);
    const ws = FakeWebSocket.instances[0];
    const before = matchesCallCount();

    ws.emitMessage({
      type: 'delete.failed',
      payload: { kind: 'match', id: 7, message: 'database is locked' },
    });
    expect(useLibraryStore.getState().deleteError).toBe(
      'The recording was not deleted: database is locked',
    );
    expect(queuedMatches()).toEqual([]);
    expect(queuedClips()).toEqual([5]);
    await flush();
    expect(matchesCallCount()).toBeGreaterThan(before); // reconciled with what the core holds

    ws.emitMessage({ type: 'delete.failed', payload: { kind: 'clip', id: 5, message: 'gone' } });
    expect(useLibraryStore.getState().deleteError).toBe('The clip was not deleted: gone');
    expect(queuedClips()).toEqual([]);
    teardown();
  });

  it('marks nothing from an older core whose status has no deletes', async () => {
    const teardown = startLibrary();
    await flush();
    useLibraryStore.setState({ queuedMatchIds: new Set([7]) });

    FakeWebSocket.instances[0].emitMessage({ type: 'status', payload: IDLE_STATUS });

    expect(queuedMatches()).toEqual([]);
    teardown();
  });

  it('marks clips as deleting while their delete requests are in flight, one at a time or in bulk', async () => {
    const held = heldDelete();
    const clips = [5, 6, 7].map((id) => ({ id, parentMatchId: 1 }));
    serveLibrary({ matches: [{ id: 1 }], clips }, (url) =>
      url.endsWith('/clips/5') ? held.answer : deleted(),
    );
    const teardown = startLibrary();
    await flush();

    const one = useLibraryStore.getState().deleteClip(5);
    const bulk = useLibraryStore.getState().deleteClips([6, 7]);
    // 5's request is still out; 6 and 7 went straight to the core behind it and are done.
    await bulk;
    expect(deletingClips()).toEqual([5]);
    expect(clipIds()).toEqual([5]);

    held.release();
    await one;
    expect(deletingClips()).toEqual([]);
    expect(clipIds()).toEqual([]);
    teardown();
  });

  it('says why a delete was not done, until the next delete starts', async () => {
    serveLibrary(
      { matches: [{ id: 7 }, { id: 8 }], clips: [{ id: 5, parentMatchId: 7 }] },
      (url) => (url.endsWith('/matches/8') ? deleted() : refused()),
    );
    const teardown = startLibrary();
    await flush();
    const deleteError = (): string | null => useLibraryStore.getState().deleteError;

    await expect(useLibraryStore.getState().deleteMatch(7)).rejects.toThrow();
    expect(deleteError()).toBe(
      'The recording was not deleted: DELETE /matches/7 failed: 500 Internal Server Error',
    );
    await useLibraryStore.getState().deleteMatch(8);
    expect(deleteError()).toBeNull();

    await expect(useLibraryStore.getState().deleteClip(5)).rejects.toThrow();
    expect(deleteError()).toBe(
      'The clip was not deleted: DELETE /clips/5 failed: 500 Internal Server Error',
    );
    teardown();
  });

  it('counts a row the core no longer has as deleted, not as a failure', async () => {
    // 7 went already (a sweep took it before this window's list caught up), so a bulk delete of 7
    // and 9 still asks for it: the core answers 404, which is what was asked for.
    serveLibrary(
      { matches: [{ id: 8 }, { id: 9 }], clips: [{ id: 6, parentMatchId: 8 }] },
      (url) =>
        url.endsWith('/matches/7')
          ? notFound('No match 7')()
          : url.endsWith('/clips/5')
            ? notFound('No clip 5')()
            : deleted(),
    );
    const teardown = startLibrary();
    await flush();

    await useLibraryStore.getState().deleteMatches([7, 9]);
    expect(useLibraryStore.getState().deleteError).toBeNull();
    expect(matchIds()).toEqual([8]);

    await useLibraryStore.getState().deleteMatch(7);
    await expect(useLibraryStore.getState().deleteClip(5)).resolves.toBe('deleted');
    await useLibraryStore.getState().deleteClips([5, 6]);
    expect(useLibraryStore.getState().deleteError).toBeNull();
    expect(clipIds()).toEqual([]);
    teardown();
  });

  it('counts the rows of a bulk delete that were not done, leaving out ones that timed out', async () => {
    serveLibrary(
      {
        matches: [{ id: 7 }, { id: 8 }, { id: 9 }],
        clips: [5, 6, 7].map((id) => ({ id, parentMatchId: 7 })),
      },
      (url) =>
        url.endsWith('/matches/8') || url.endsWith('/clips/7')
          ? deleted()
          : url.endsWith('/clips/6')
            ? timedOut()
            : refused(),
    );
    const teardown = startLibrary();
    await flush();
    const deleteError = (): string | null => useLibraryStore.getState().deleteError;

    await useLibraryStore.getState().deleteMatches([7, 8, 9]);
    expect(deleteError()).toBe(
      '2 of 3 recordings were not deleted: DELETE /matches/7 failed: 500 Internal Server Error',
    );
    await useLibraryStore.getState().deleteClips([5, 6, 7]);
    expect(deleteError()).toBe(
      '1 of 3 clips was not deleted: DELETE /clips/5 failed: 500 Internal Server Error',
    );
    teardown();
  });

  it('does not report a delete that timed out: the core still finishes it', async () => {
    serveLibrary(
      { matches: [{ id: 7 }, { id: 8 }], clips: [5, 6].map((id) => ({ id, parentMatchId: 7 })) },
      timedOut,
    );
    const teardown = startLibrary();
    await flush();

    await expect(useLibraryStore.getState().deleteMatch(7)).rejects.toThrow();
    await useLibraryStore.getState().deleteMatches([8]);
    await expect(useLibraryStore.getState().deleteClip(5)).rejects.toThrow();
    await useLibraryStore.getState().deleteClips([6]);

    expect(useLibraryStore.getState().deleteError).toBeNull();
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
