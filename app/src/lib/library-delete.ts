// Pure state transforms for the library's delete actions (store/library.ts). Kept React-free here so
// the exact bookkeeping — which rows drop, which merely lose their video, and when the player's
// selection must clear — is unit-testable (see library-delete.test.ts).
import type { MatchSummary, Clip } from '../api/client';

// The slice of the library store a delete touches.
export interface DeleteSlice {
  readonly matches: readonly MatchSummary[];
  readonly clips: readonly Clip[];
  readonly selectedMatchId: number | null;
  readonly selectedClipId: number | null;
}

// Whether the current player selection points at one of the deleted matches (in which case it must
// clear — for a full delete the match is gone; for a with-clips delete its video file is gone, and a
// stale selection would leave the player streaming the unlinked .mp4).
function selectionCleared(s: DeleteSlice, deleted: ReadonlySet<number>): {
  readonly selectedMatchId: number | null;
  readonly selectedClipId: number | null;
} {
  const hit = s.selectedMatchId !== null && deleted.has(s.selectedMatchId);
  return {
    selectedMatchId: hit ? null : s.selectedMatchId,
    selectedClipId: hit ? null : s.selectedClipId,
  };
}

// Full delete (a clipless recording): the match rows go entirely. Any clip still pointing at a
// dropped row is pruned defensively — with a fresh clips list there are none (the server only fully
// deletes clipless rows), but a stale local list must not keep entries whose parent is gone.
export function applyMatchesDeleted(s: DeleteSlice, deleted: ReadonlySet<number>): DeleteSlice {
  return {
    matches: s.matches.filter((m) => !deleted.has(m.id)),
    clips: s.clips.filter((c) => !deleted.has(c.parentMatchId)),
    ...selectionCleared(s, deleted),
  };
}

// With-clips delete: the recordings' videos are gone but the rows SURVIVE with nulled paths (the
// retention-sweep end state the server leaves), and every clip is untouched. A clip auto-play
// selection therefore stays live — the clip's file is intact and the stub row still parents it, so
// tearing the player down mid-clip would be gratuitous. Only a full-VOD selection (selectedClipId
// null) clears, since the player would otherwise keep streaming the unlinked .mp4.
export function applyMatchVideosDeleted(s: DeleteSlice, deleted: ReadonlySet<number>): DeleteSlice {
  const selection =
    s.selectedClipId !== null
      ? { selectedMatchId: s.selectedMatchId, selectedClipId: s.selectedClipId }
      : selectionCleared(s, deleted);
  return {
    matches: s.matches.map((m) =>
      deleted.has(m.id) ? { ...m, videoPath: null, thumbPath: null, fileSizeBytes: null } : m,
    ),
    clips: s.clips,
    ...selection,
  };
}

// Mirror of the server's DELETE /matches/{id} rule — a recording delete never touches clips: a
// recording WITH clips becomes a videoless stub row (the clips need their parent), a clipless one
// drops entirely.
export function applyRecordingsDeleted(s: DeleteSlice, deleted: ReadonlySet<number>): DeleteSlice {
  const withClips = new Set(
    s.clips.filter((c) => deleted.has(c.parentMatchId)).map((c) => c.parentMatchId),
  );
  const fully = new Set([...deleted].filter((id) => !withClips.has(id)));
  return applyMatchesDeleted(applyMatchVideosDeleted(s, withClips), fully);
}

// What the core says a recording delete did, mirrored: 'stubbed' keeps the rows videoless, 'deleted'
// drops them, and no outcome (an older core) falls back to the local guess.
export function applyRecordingsOutcome(
  s: DeleteSlice,
  deleted: ReadonlySet<number>,
  outcome: 'deleted' | 'stubbed' | null,
): DeleteSlice {
  if (outcome === 'stubbed') return applyMatchVideosDeleted(s, deleted);
  if (outcome === 'deleted') return applyMatchesDeleted(s, deleted);
  return applyRecordingsDeleted(s, deleted);
}

// The recording a match.deleted frame says is done ({ id, outcome }), or null for any other frame or a
// payload without a numeric id. The core sends it once a recording delete has gone through, wherever
// it was asked for and however long it waited, so for a delete the core queued it is the only word
// that it is done. An outcome other than 'deleted' / 'stubbed' reads as null (the local guess).
export function matchDeleteDone(evt: {
  readonly type: string;
  readonly payload: unknown;
}): { readonly id: number; readonly outcome: 'deleted' | 'stubbed' | null } | null {
  if (evt.type !== 'match.deleted') return null;
  const payload = evt.payload;
  if (typeof payload !== 'object' || payload === null) return null;
  const { id, outcome } = payload as { id?: unknown; outcome?: unknown };
  if (typeof id !== 'number') return null;
  return { id, outcome: outcome === 'deleted' || outcome === 'stubbed' ? outcome : null };
}

// The rows to show as being deleted: those this window has a delete request in flight for, and those
// the core still has a delete of, queued behind its maintenance lock. One set for a table or the
// player's strip; either input is handed back as it is when the other is empty.
export function deletingRows(
  inFlight: ReadonlySet<number>,
  queued: ReadonlySet<number>,
): ReadonlySet<number> {
  if (queued.size === 0) return inFlight;
  if (inFlight.size === 0) return queued;
  return new Set([...inFlight, ...queued]);
}

// The queued set a status sets: the ids it lists as the core's pending deletes. `current` is handed
// back as it is when it holds the same ids, so a status every few seconds that changes nothing
// re-renders nothing.
export function idsListed(
  current: ReadonlySet<number>,
  listed: readonly number[],
): ReadonlySet<number> {
  const next = new Set(listed);
  if (next.size === current.size && [...next].every((id) => current.has(id))) return current;
  return next;
}

// Clip delete: drop the clip row; clear a clip auto-play selection that pointed at it (the parent
// match selection survives — only the clip is gone).
export function applyClipDeleted(s: DeleteSlice, clipId: number): DeleteSlice {
  return applyClipsDeleted(s, new Set([clipId]));
}

// Bulk clip delete (the Clips bucket's multi-select): same rule as the single delete, applied to
// every id in one state update.
export function applyClipsDeleted(s: DeleteSlice, deleted: ReadonlySet<number>): DeleteSlice {
  return {
    matches: s.matches,
    clips: s.clips.filter((c) => !deleted.has(c.id)),
    selectedMatchId: s.selectedMatchId,
    selectedClipId:
      s.selectedClipId !== null && deleted.has(s.selectedClipId) ? null : s.selectedClipId,
  };
}
