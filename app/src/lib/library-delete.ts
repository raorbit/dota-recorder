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
// clear — for a full delete the match is gone; for a keep-clips delete its video file is gone, and a
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

// Full delete: the match rows go, and their clips go with them (the server cascades the rows and
// unlinks the clip files).
export function applyMatchesDeleted(s: DeleteSlice, deleted: ReadonlySet<number>): DeleteSlice {
  return {
    matches: s.matches.filter((m) => !deleted.has(m.id)),
    clips: s.clips.filter((c) => !deleted.has(c.parentMatchId)),
    ...selectionCleared(s, deleted),
  };
}

// Keep-clips delete: the recordings' videos are gone but the rows SURVIVE with nulled paths (the
// retention-sweep end state the server leaves), and every clip is untouched.
export function applyMatchVideosDeleted(s: DeleteSlice, deleted: ReadonlySet<number>): DeleteSlice {
  return {
    matches: s.matches.map((m) =>
      deleted.has(m.id) ? { ...m, videoPath: null, thumbPath: null, fileSizeBytes: null } : m,
    ),
    clips: s.clips,
    ...selectionCleared(s, deleted),
  };
}

// Clip delete: drop the clip row; clear a clip auto-play selection that pointed at it (the parent
// match selection survives — only the clip is gone).
export function applyClipDeleted(s: DeleteSlice, clipId: number): DeleteSlice {
  return {
    matches: s.matches,
    clips: s.clips.filter((c) => c.id !== clipId),
    selectedMatchId: s.selectedMatchId,
    selectedClipId: s.selectedClipId === clipId ? null : s.selectedClipId,
  };
}
