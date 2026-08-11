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
