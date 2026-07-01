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
