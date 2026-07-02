import { describe, expect, it } from 'vitest';
import type { MatchSummary, Clip, BucketCounts } from '../api/client';
import { mergeLibraryLoad, type LibraryLoadPrev, type Settled } from './library-load';

// A MatchSummary with every field defaulted; tests override only what they exercise.
function mkMatch(id: number): MatchSummary {
  return {
    id,
    dotaMatchId: null,
    recordKind: 'match',
    enrichmentState: 'enriched',
    hero: null,
    kills: null,
    deaths: null,
    assists: null,
    gpm: null,
    xpm: null,
    netWorth: null,
    lastHits: null,
    result: null,
    lobbyType: null,
    gameMode: null,
    rankTier: null,
    mmrDelta: null,
    durationS: null,
    playedAt: null,
    videoPath: null,
    thumbPath: null,
    fileSizeBytes: null,
    starred: false,
    createdAt: 0,
    recordStartedWallMs: null,
  };
}

function mkClip(id: number, parentMatchId: number): Clip {
  return {
    id,
    parentMatchId,
    kind: 'manual',
    triggerReason: null,
    startOffsetS: 0,
    endOffsetS: 10,
    label: null,
    videoPath: null,
    thumbPath: null,
    fileSizeBytes: null,
    status: 'ready',
    error: null,
    createdAt: 0,
    starred: false,
  };
}

const COUNTS: BucketCounts = {
  ranked: 3,
  unranked: 0,
  turbo: 0,
  abilityDraft: 0,
  manual: 0,
  clips: 0,
  unsorted: 0,
};
const PREV_COUNTS: BucketCounts = { ...COUNTS, ranked: 9 };

function ok<T>(value: T): Settled<T> {
  return { status: 'fulfilled', value };
}
function fail<T>(): Settled<T> {
  return { status: 'rejected', reason: new Error('boom') };
}

// A default previous slice with a populated list, counts, and an open match selection.
function prev(over: Partial<LibraryLoadPrev> = {}): LibraryLoadPrev {
  return {
    matches: [mkMatch(1), mkMatch(2)],
    counts: PREV_COUNTS,
    clips: [],
    selectedMatchId: 1,
    selectedClipId: null,
    ...over,
  };
}

describe('mergeLibraryLoad', () => {
  it('success path: takes fetched slices, ready state, keeps a still-present selection', () => {
    const res = mergeLibraryLoad(
      ok([mkMatch(1), mkMatch(2), mkMatch(3)]),
      ok(COUNTS),
      ok([]),
      prev(),
    );
    expect(res.matches.map((m) => m.id)).toEqual([1, 2, 3]);
    expect(res.counts).toBe(COUNTS);
    expect(res.loadState).toBe('ready');
    expect(res.selectedMatchId).toBe(1);
    expect(res.selectedClipId).toBeNull();
  });

  it('clears the selection only when its fetch SUCCEEDED and the id is genuinely gone', () => {
    const res = mergeLibraryLoad(ok([mkMatch(2), mkMatch(3)]), ok(COUNTS), ok([]), prev());
    expect(res.selectedMatchId).toBeNull();
  });

  it('total failure: keeps every previous slice and surfaces error, selection untouched', () => {
    const p = prev();
    const res = mergeLibraryLoad(fail(), fail(), fail(), p);
    expect(res.matches).toBe(p.matches);
    expect(res.counts).toBe(p.counts);
    expect(res.clips).toBe(p.clips);
    expect(res.loadState).toBe('error');
    expect(res.selectedMatchId).toBe(1);
  });

  it('partial failure (matches rejected): keeps old matches AND the selection', () => {
    const p = prev();
    const res = mergeLibraryLoad(fail(), ok(COUNTS), ok([]), p);
    expect(res.matches).toBe(p.matches);
    expect(res.counts).toBe(COUNTS); // the fetch that succeeded still updates
    expect(res.loadState).toBe('ready');
    expect(res.selectedMatchId).toBe(1);
  });

  it('partial failure (counts rejected): keeps old counts, matches + selection from the good fetch', () => {
    const res = mergeLibraryLoad(ok([mkMatch(1)]), fail(), ok([]), prev());
    expect(res.counts).toBe(PREV_COUNTS);
    expect(res.matches.map((m) => m.id)).toEqual([1]);
    expect(res.loadState).toBe('ready');
    expect(res.selectedMatchId).toBe(1);
  });

  it('a clip selection survives while its parent match still resolves in the list', () => {
    const p = prev({ selectedMatchId: 5, selectedClipId: 42, clips: [mkClip(42, 5)] });
    const res = mergeLibraryLoad(ok([mkMatch(5)]), ok(COUNTS), ok([mkClip(42, 5)]), p);
    expect(res.selectedMatchId).toBe(5);
    expect(res.selectedClipId).toBe(42);
  });

  it('drops a clip selection when the clip fetch succeeds and the clip is gone', () => {
    const p = prev({ selectedMatchId: 5, selectedClipId: 42, clips: [mkClip(42, 5)] });
    const res = mergeLibraryLoad(ok([]), ok(COUNTS), ok([]), p);
    expect(res.selectedMatchId).toBeNull();
    expect(res.selectedClipId).toBeNull();
  });

  it('keeps a clip selection when the clips fetch failed (cannot prove it is gone)', () => {
    const p = prev({ selectedMatchId: 5, selectedClipId: 42, clips: [mkClip(42, 5)] });
    const res = mergeLibraryLoad(ok([]), ok(COUNTS), fail(), p);
    expect(res.clips).toBe(p.clips);
    expect(res.selectedMatchId).toBe(5);
    expect(res.selectedClipId).toBe(42);
  });

  it('with no open selection, leaves selection null on a partial failure', () => {
    const p = prev({ selectedMatchId: null, selectedClipId: null });
    const res = mergeLibraryLoad(fail(), ok(COUNTS), ok([]), p);
    expect(res.selectedMatchId).toBeNull();
    expect(res.selectedClipId).toBeNull();
  });
});
