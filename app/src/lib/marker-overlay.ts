// Pure decision logic for the video player's parent-VOD marker/pause overlay.
//
// Markers and pause spans are positioned against the media element's duration and their
// video offsets are in the PARENT-VOD timebase. That math is only valid while the player
// is over the full VOD: when a clip is playing, the <video> src is the clip stream, so
// duration/currentTime are clip-relative — a parent-VOD offset would mis-position (piling
// every marker at the right edge) and a marker-click seek would clamp to the clip end.
//
// Kept React/JSX-free so it can be unit-tested in plain Node (see marker-overlay.test.ts).

// Whether the parent-VOD marker/pause overlay may render and honor marker-click seeks.
//   canPosition — a usable positive duration exists to position bars against
//   activeClipId — the clip the media element is playing, or null when over the full VOD
// Bars are shown/clickable only over the full VOD (activeClipId === null); during clip
// playback the parent-VOD timebase doesn't apply, so the overlay is hidden instead of
// mispositioned.
export function shouldShowVodOverlay(canPosition: boolean, activeClipId: number | null): boolean {
  return canPosition && activeClipId === null;
}

// Seconds of context to land BEFORE a marker's event on a marker-click seek. Jumping to the
// exact offset shows only the aftermath (the death is already on the ground); landing a few
// seconds early lets the play actually unfold. Markers stay positioned at the true offset —
// only the seek target shifts.
export const MARKER_LEAD_IN_S = 5;

// Seek target for a marker click: the event offset minus the lead-in, floored at 0 so
// markers inside the first seconds of the VOD still seek to the start.
export function markerSeekTarget(videoOffsetS: number): number {
  return Math.max(0, videoOffsetS - MARKER_LEAD_IN_S);
}
