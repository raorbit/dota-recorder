import { describe, expect, it } from 'vitest';
import { MARKER_LEAD_IN_S, markerSeekTarget, shouldShowVodOverlay } from './marker-overlay';

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

describe('markerSeekTarget', () => {
  it('lands the lead-in before the event', () => {
    expect(markerSeekTarget(120)).toBe(120 - MARKER_LEAD_IN_S);
  });

  it('floors at the start of the VOD for early markers', () => {
    // An event 2s into the recording can't seek to a negative time.
    expect(markerSeekTarget(2)).toBe(0);
  });

  it('floors exactly-at-lead-in offsets to 0', () => {
    expect(markerSeekTarget(MARKER_LEAD_IN_S)).toBe(0);
  });
});
