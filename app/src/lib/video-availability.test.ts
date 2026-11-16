import { describe, expect, it } from 'vitest';
import {
  shouldShowNoVideoPlaceholder,
  retentionAffectsMatch,
  libraryEventRemovesVod,
  afterStripClipDelete,
  releaseStaleMedia,
  type PlaybackReadout,
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

describe('afterStripClipDelete', () => {
  const clip = { id: 5, parentMatchId: 2 };
  // The delete was asked for while selection 4 was open.
  const askedIn = 4;

  it('drops the clip and leaves it for the full VOD while it is still the one playing', () => {
    expect(
      afterStripClipDelete(clip, askedIn, { matchId: 2, selection: 4, activeClipId: 5 }),
    ).toEqual({ dropFromStrip: true, refetchStrip: false, leaveClip: true });
  });

  it('keeps whatever plays now when another clip or the full VOD took over meanwhile', () => {
    expect(
      afterStripClipDelete(clip, askedIn, { matchId: 2, selection: 4, activeClipId: 6 }),
    ).toEqual({ dropFromStrip: true, refetchStrip: false, leaveClip: false });
    expect(
      afterStripClipDelete(clip, askedIn, { matchId: 2, selection: 4, activeClipId: null }),
    ).toEqual({ dropFromStrip: true, refetchStrip: false, leaveClip: false });
  });

  it('fetches the strip again once the same match was left and opened again meanwhile', () => {
    // Dropping the clip discards the strip fetch in flight, which may be the reopening's own:
    // without a fetch after the delete the strip would stay empty until the next clip frame.
    expect(
      afterStripClipDelete(clip, askedIn, { matchId: 2, selection: 6, activeClipId: null }),
    ).toEqual({ dropFromStrip: true, refetchStrip: true, leaveClip: false });
    // The reopening listed the clip (its fetch predates the delete), and it was played again.
    expect(
      afterStripClipDelete(clip, askedIn, { matchId: 2, selection: 6, activeClipId: 5 }),
    ).toEqual({ dropFromStrip: true, refetchStrip: true, leaveClip: true });
  });

  it('touches neither the strip nor playback once another match is open', () => {
    for (const activeClipId of [null, 9]) {
      for (const selection of [4, 5]) {
        expect(
          afterStripClipDelete(clip, askedIn, { matchId: 3, selection, activeClipId }),
        ).toEqual({ dropFromStrip: false, refetchStrip: false, leaveClip: false });
      }
    }
    expect(
      afterStripClipDelete(clip, askedIn, { matchId: null, selection: 5, activeClipId: null }),
    ).toEqual({ dropFromStrip: false, refetchStrip: false, leaveClip: false });
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

  // The player's transport setters, recording what they were set to.
  function readout(): PlaybackReadout & { calls: string[] } {
    const calls: string[] = [];
    return {
      calls,
      setPlaying: (playing) => calls.push(`playing ${playing}`),
      setProgress: (percent) => calls.push(`progress ${percent}`),
      setCurrentTimeS: (seconds) => calls.push(`time ${seconds}`),
      setMediaDurationS: (seconds) => calls.push(`duration ${seconds}`),
    };
  }

  it('stops and empties an element still holding the previous recording once the source is gone', () => {
    const v = media('http://127.0.0.1:3224/matches/1/video/stream');
    const r = readout();
    expect(releaseStaleMedia(v, undefined, r)).toBe(true);
    expect(v.calls).toEqual(['pause', 'remove src', 'load']);
    // ...and the play glyph, playhead, time and length stop describing the recording that was there.
    expect(r.calls).toEqual(['playing false', 'progress 0', 'time 0', 'duration null']);
  });

  it('leaves an element alone while it has a source, or when it holds nothing', () => {
    const r = readout();
    const playing = media('http://127.0.0.1:3224/matches/1/video/stream');
    expect(releaseStaleMedia(playing, 'http://127.0.0.1:3224/matches/2/video/stream', r)).toBe(
      false,
    );
    expect(playing.calls).toEqual([]);

    const empty = media('');
    expect(releaseStaleMedia(empty, undefined, r)).toBe(false);
    expect(empty.calls).toEqual([]);

    expect(releaseStaleMedia(null, undefined, r)).toBe(false);
    expect(r.calls).toEqual([]);
  });
});
