// Pure decision logic for the video player's VOD-availability handling, and for what a clip delete
// from its strip still changes once it finishes. Kept React/DOM-free so it unit-tests in plain Node
// (see video-availability.test.ts); VideoPlayer.tsx is a thin consumer.

// Whether to render the "no video · recording removed" placeholder. Show it ONLY when there is neither
// a playable full VOD nor a clip currently playing: a swept/pruned match (no VOD) that is playing a
// CLIP still has something on screen, so the placeholder must not paint over it.
//   hasVod       — the full-VOD stream is available (videoUrl !== null)
//   activeClipId — the clip the media element is playing, or null when over the full VOD
export function shouldShowNoVideoPlaceholder(
  hasVod: boolean,
  activeClipId: number | null,
): boolean {
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

// The parts of a media element releaseStaleMedia drives (the player's <video>).
export interface ReleasableMedia {
  readonly currentSrc: string;
  pause(): void;
  removeAttribute(name: string): void;
  load(): void;
}

// Stops and empties a media element whose source went away. When the player's source becomes
// undefined React only drops the src attribute, and a media element keeps playing what it already
// loaded: selecting a recording with no video (or the open one being deleted or swept) left the
// previous VOD playing, sound and all, behind the "recording removed" placeholder. Returns whether
// there was anything to release.
export function releaseStaleMedia(media: ReleasableMedia | null, src: string | undefined): boolean {
  if (media === null || src !== undefined || media.currentSrc === '') return false;
  media.pause();
  media.removeAttribute('src');
  media.load();
  return true;
}

// What a clip delete asked for from the player's strip should still change once it has gone through.
// That can be minutes after the click (the delete waits behind an archive pass), so it is judged
// against what the player shows by then, not at the click: the strip drops the clip only while it
// still lists that clip's match (dropping it means discarding any strip fetch in flight, which by then
// may be another match's), and playback leaves the clip for the full VOD only if the deleted clip is
// still the one playing.
export function afterStripClipDelete(
  deleted: { readonly id: number; readonly parentMatchId: number },
  now: { readonly matchId: number | null; readonly activeClipId: number | null },
): { readonly dropFromStrip: boolean; readonly leaveClip: boolean } {
  const dropFromStrip = now.matchId === deleted.parentMatchId;
  return { dropFromStrip, leaveClip: dropFromStrip && now.activeClipId === deleted.id };
}

// Whether a library event took the given match's VOD away: a retention.swept that pruned it, or a
// match.deleted for it (a delete that may have finished after the request that asked for it gave up).
// The open player then re-derives full-VOD availability instead of streaming a file that is gone.
export function libraryEventRemovesVod(
  evt: { readonly type: string; readonly payload: unknown },
  matchId: number,
): boolean {
  if (evt.type === 'retention.swept') return retentionAffectsMatch(evt.payload, matchId);
  if (evt.type !== 'match.deleted') return false;
  const payload = evt.payload;
  return (
    typeof payload === 'object' && payload !== null && (payload as { id?: unknown }).id === matchId
  );
}
