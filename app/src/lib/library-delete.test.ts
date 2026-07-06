import { describe, expect, it } from 'vitest';
import type { MatchSummary, Clip } from '../api/client';
import {
  applyMatchesDeleted,
  applyMatchVideosDeleted,
  applyRecordingsDeleted,
  applyClipDeleted,
  type DeleteSlice,
} from './library-delete';

// A MatchSummary with every field defaulted; tests override only what they exercise.
function mkMatch(id: number, videoPath: string | null = `C:/vods/${id}.mp4`): MatchSummary {
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
    videoPath,
    thumbPath: videoPath === null ? null : `C:/vods/${id}.jpg`,
    fileSizeBytes: videoPath === null ? null : 1024,
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
    videoPath: `C:/vods/clips/${id}.mp4`,
    thumbPath: null,
    fileSizeBytes: null,
    status: 'ready',
    error: null,
    createdAt: 0,
    starred: false,
  };
}

function slice(overrides: Partial<DeleteSlice> = {}): DeleteSlice {
  return {
    matches: [mkMatch(1), mkMatch(2), mkMatch(3)],
    clips: [mkClip(10, 1), mkClip(11, 1), mkClip(12, 2)],
    selectedMatchId: null,
    selectedClipId: null,
    ...overrides,
  };
}

describe('applyMatchesDeleted (full delete of clipless rows)', () => {
  it('drops the deleted rows (and defensively prunes any clip left pointing at them)', () => {
    const next = applyMatchesDeleted(slice(), new Set([1]));
    expect(next.matches.map((m) => m.id)).toEqual([2, 3]);
    expect(next.clips.map((c) => c.id)).toEqual([12]);
  });

  it('clears the selection when the open match is deleted', () => {
    const next = applyMatchesDeleted(
      slice({ selectedMatchId: 1, selectedClipId: 10 }),
      new Set([1]),
    );
    expect(next.selectedMatchId).toBeNull();
    expect(next.selectedClipId).toBeNull();
  });

  it('keeps an unrelated selection', () => {
    const next = applyMatchesDeleted(
      slice({ selectedMatchId: 2, selectedClipId: 12 }),
      new Set([1]),
    );
    expect(next.selectedMatchId).toBe(2);
    expect(next.selectedClipId).toBe(12);
  });

  it('handles a multi-id set (the table bulk delete)', () => {
    const next = applyMatchesDeleted(slice(), new Set([1, 2]));
    expect(next.matches.map((m) => m.id)).toEqual([3]);
    expect(next.clips).toEqual([]);
  });
});

describe('applyRecordingsDeleted (the server rule: clips are never touched)', () => {
  it('a recording with clips becomes a videoless stub; a clipless one drops entirely', () => {
    // Match 1 carries clips 10+11, match 3 has none.
    const next = applyRecordingsDeleted(slice(), new Set([1, 3]));
    expect(next.matches.map((m) => m.id)).toEqual([1, 2]);
    const stub = next.matches[0]!;
    expect(stub.videoPath).toBeNull();
    expect(stub.thumbPath).toBeNull();
    expect(stub.fileSizeBytes).toBeNull();
    // Every clip survives, still parented to the stub.
    expect(next.clips.map((c) => c.id)).toEqual([10, 11, 12]);
  });

  it('clears the selection whichever fate the open match had', () => {
    const stubbed = applyRecordingsDeleted(slice({ selectedMatchId: 1 }), new Set([1]));
    expect(stubbed.selectedMatchId).toBeNull();
    const dropped = applyRecordingsDeleted(slice({ selectedMatchId: 3 }), new Set([3]));
    expect(dropped.selectedMatchId).toBeNull();
  });
});

describe('applyMatchVideosDeleted (with-clips delete)', () => {
  it('nulls the video/thumb/size but KEEPS the row and every clip', () => {
    const next = applyMatchVideosDeleted(slice(), new Set([1]));
    expect(next.matches.map((m) => m.id)).toEqual([1, 2, 3]);
    const swept = next.matches[0]!;
    expect(swept.videoPath).toBeNull();
    expect(swept.thumbPath).toBeNull();
    expect(swept.fileSizeBytes).toBeNull();
    // Untouched rows keep their paths; clips are entirely untouched.
    expect(next.matches[1]!.videoPath).not.toBeNull();
    expect(next.clips.map((c) => c.id)).toEqual([10, 11, 12]);
  });

  it('clears the selection when the open match loses its video (the player would stream a gone file)', () => {
    const next = applyMatchVideosDeleted(
      slice({ selectedMatchId: 1, selectedClipId: 10 }),
      new Set([1]),
    );
    expect(next.selectedMatchId).toBeNull();
    expect(next.selectedClipId).toBeNull();
  });

  it('keeps an unrelated selection', () => {
    const next = applyMatchVideosDeleted(slice({ selectedMatchId: 3 }), new Set([1]));
    expect(next.selectedMatchId).toBe(3);
  });
});

describe('applyClipDeleted', () => {
  it('drops just the clip', () => {
    const next = applyClipDeleted(slice(), 11);
    expect(next.clips.map((c) => c.id)).toEqual([10, 12]);
    expect(next.matches).toHaveLength(3);
  });

  it('clears a clip auto-play selection pointing at it, keeping the parent match open', () => {
    const next = applyClipDeleted(slice({ selectedMatchId: 1, selectedClipId: 10 }), 10);
    expect(next.selectedMatchId).toBe(1);
    expect(next.selectedClipId).toBeNull();
  });

  it('keeps a selection pointing at another clip', () => {
    const next = applyClipDeleted(slice({ selectedMatchId: 1, selectedClipId: 11 }), 10);
    expect(next.selectedClipId).toBe(11);
  });
});
