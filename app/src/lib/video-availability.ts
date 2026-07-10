// Pure decision logic for the video player's VOD-availability handling. Kept React/DOM-free so it
// unit-tests in plain Node (see video-availability.test.ts); VideoPlayer.tsx is a thin consumer.

// Whether to render the "no video · recording removed" placeholder. Show it ONLY when there is neither
// a playable full VOD nor a clip currently playing: a swept/pruned match (no VOD) that is playing a
// CLIP still has something on screen, so the placeholder must not paint over it.
//   hasVod       — the full-VOD stream is available (videoUrl !== null)
//   activeClipId — the clip the media element is playing, or null when over the full VOD
export function shouldShowNoVideoPlaceholder(hasVod: boolean, activeClipId: number | null): boolean {
  return !hasVod && activeClipId === null;
}

// Whether a retention.swept frame's payload pruned the given match's VOD — i.e. its `deletedIds` array
// contains matchId. The open player uses this to refresh full-VOD availability (the file may now be
// gone) instead of trusting the value cached when the match was first selected. A malformed/absent
// deletedIds yields false (nothing proven pruned).
export function retentionAffectsMatch(payload: unknown, matchId: number): boolean {
  if (typeof payload !== 'object' || payload === null) return false;
  const ids = (payload as { deletedIds?: unknown }).deletedIds;
  return Array.isArray(ids) && ids.includes(matchId);
}
