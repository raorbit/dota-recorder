import { describe, expect, it } from 'vitest';
import { shouldShowVodOverlay } from './marker-overlay';

describe('shouldShowVodOverlay', () => {
  it('shows the overlay when positioned against a usable duration over the full VOD', () => {
    // activeClipId === null => player is over the full VOD, so parent-VOD offsets are valid.
    expect(shouldShowVodOverlay(true, null)).toBe(true);
  });

  it('hides the overlay while a clip is playing, even with a usable duration', () => {
    // A clip is active: duration/offsets are clip-relative, so parent-VOD bars would misalign.
    expect(shouldShowVodOverlay(true, 42)).toBe(false);
  });

  it('hides the overlay without a usable duration, over the full VOD', () => {
    // No positive duration to position against (seeded / no-file row).
    expect(shouldShowVodOverlay(false, null)).toBe(false);
  });

  it('hides the overlay when neither condition holds', () => {
    expect(shouldShowVodOverlay(false, 7)).toBe(false);
  });
});
