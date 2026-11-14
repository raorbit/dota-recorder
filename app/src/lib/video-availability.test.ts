import { describe, expect, it } from 'vitest';
import {
  shouldShowNoVideoPlaceholder,
  retentionAffectsMatch,
  libraryEventRemovesVod,
  releaseStaleMedia,
  type ReleasableMedia,
} from './video-availability';

describe('shouldShowNoVideoPlaceholder', () => {
  it('shows the placeholder for a no-VOD row over the full VOD (no clip playing)', () => {
    expect(shouldShowNoVideoPlaceholder(false, null)).toBe(true);
  });

  it('hides the placeholder while a clip is playing, even with no full VOD', () => {
    // The clip stream is on screen — the placeholder must not paint over it.
    expect(shouldShowNoVideoPlaceholder(false, 42)).toBe(false);
  });

  it('hides the placeholder when a full VOD is available', () => {
    expect(shouldShowNoVideoPlaceholder(true, null)).toBe(false);
    expect(shouldShowNoVideoPlaceholder(true, 42)).toBe(false);
  });
});

describe('retentionAffectsMatch', () => {
  it('is true when the swept payload deletedIds contains the match', () => {
    expect(retentionAffectsMatch({ freedBytes: 10, deletedIds: [1, 2, 3] }, 2)).toBe(true);
  });

  it('is false when the match is not among the swept ids', () => {
    expect(retentionAffectsMatch({ freedBytes: 10, deletedIds: [1, 3] }, 2)).toBe(false);
  });

  it('is false for an empty or missing deletedIds', () => {
    expect(retentionAffectsMatch({ deletedIds: [] }, 2)).toBe(false);
    expect(retentionAffectsMatch({ freedBytes: 10 }, 2)).toBe(false);
  });

  it('is false for a malformed payload', () => {
    expect(retentionAffectsMatch(null, 2)).toBe(false);
    expect(retentionAffectsMatch('nope', 2)).toBe(false);
    expect(retentionAffectsMatch({ deletedIds: 'x' }, 2)).toBe(false);
  });
});

describe('libraryEventRemovesVod', () => {
  it('is true for a retention.swept that pruned the match', () => {
    expect(
      libraryEventRemovesVod({ type: 'retention.swept', payload: { deletedIds: [2] } }, 2),
    ).toBe(true);
    expect(
      libraryEventRemovesVod({ type: 'retention.swept', payload: { deletedIds: [3] } }, 2),
    ).toBe(false);
  });

  it('is true for a match.deleted of the match, whichever branch the delete took', () => {
    for (const outcome of ['deleted', 'stubbed']) {
      expect(
        libraryEventRemovesVod({ type: 'match.deleted', payload: { id: 2, outcome } }, 2),
      ).toBe(true);
    }
    expect(libraryEventRemovesVod({ type: 'match.deleted', payload: { id: 3 } }, 2)).toBe(false);
  });

  it('is false for other events and malformed payloads', () => {
    expect(libraryEventRemovesVod({ type: 'match.enriched', payload: { id: 2 } }, 2)).toBe(false);
    expect(libraryEventRemovesVod({ type: 'match.deleted', payload: null }, 2)).toBe(false);
    expect(libraryEventRemovesVod({ type: 'match.deleted', payload: { id: '2' } }, 2)).toBe(false);
  });
});

describe('releaseStaleMedia', () => {
  function media(currentSrc: string): ReleasableMedia & { calls: string[] } {
    const calls: string[] = [];
    return {
      currentSrc,
      calls,
      pause: () => calls.push('pause'),
      removeAttribute: (name) => calls.push(`remove ${name}`),
      load: () => calls.push('load'),
    };
  }

  it('stops and empties an element still holding the previous recording once the source is gone', () => {
    const v = media('http://127.0.0.1:3224/matches/1/video/stream');
    expect(releaseStaleMedia(v, undefined)).toBe(true);
    expect(v.calls).toEqual(['pause', 'remove src', 'load']);
  });

  it('leaves an element alone while it has a source, or when it holds nothing', () => {
    const playing = media('http://127.0.0.1:3224/matches/1/video/stream');
    expect(releaseStaleMedia(playing, 'http://127.0.0.1:3224/matches/2/video/stream')).toBe(false);
    expect(playing.calls).toEqual([]);

    const empty = media('');
    expect(releaseStaleMedia(empty, undefined)).toBe(false);
    expect(empty.calls).toEqual([]);

    expect(releaseStaleMedia(null, undefined)).toBe(false);
  });
});
