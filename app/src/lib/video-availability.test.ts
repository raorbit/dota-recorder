import { describe, expect, it } from 'vitest';
import { shouldShowNoVideoPlaceholder, retentionAffectsMatch } from './video-availability';

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
