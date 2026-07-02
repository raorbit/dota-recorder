// Pure logic behind the Browse screen's load(): given the three settled fetch results
// (matches, bucket counts, clips) plus the store's PREVIOUS slices and current selection,
// decides the next data slices, the load state, and whether the open selection survives.
//
// Kept React-free so it can be unit-tested in a plain Node environment (see
// library-load.test.ts). load() in ../store/library.ts is a thin wrapper around it.
import type { MatchSummary, Clip, BucketCounts } from '../api/client';

// A resolved-or-empty fetch result, matching Promise.allSettled entries.
export type Settled<T> = PromiseSettledResult<T>;

export interface LibraryLoadPrev {
  readonly matches: readonly MatchSummary[];
  readonly counts: BucketCounts;
  readonly clips: readonly Clip[];
  readonly selectedMatchId: number | null;
  readonly selectedClipId: number | null;
}

export interface LibraryLoadResult {
  readonly matches: readonly MatchSummary[];
  readonly counts: BucketCounts;
  readonly clips: readonly Clip[];
  readonly loadState: 'ready' | 'error';
  readonly selectedMatchId: number | null;
  readonly selectedClipId: number | null;
}

// Merge a load()'s settled fetches into the next store slice. A rejected individual fetch KEEPS
// the previous slice (rather than blanking it) so one failing endpoint — likeliest right after a
// match records, when the core is busiest — cannot wipe the table or tear down the playing video.
// Selection survival is evaluated only against a list whose fetch actually succeeded: if the matches
// fetch failed we can't know the id vanished, so the selection stays. Error state is reserved for the
// all-three-failed case (core unreachable).
export function mergeLibraryLoad(
  matchesRes: Settled<readonly MatchSummary[]>,
  countsRes: Settled<BucketCounts>,
  clipsRes: Settled<readonly Clip[]>,
  prev: LibraryLoadPrev,
): LibraryLoadResult {
  const matches = matchesRes.status === 'fulfilled' ? matchesRes.value : prev.matches;
  const counts = countsRes.status === 'fulfilled' ? countsRes.value : prev.counts;
  const clips = clipsRes.status === 'fulfilled' ? clipsRes.value : prev.clips;

  // If ALL calls failed the core is unreachable; surface an error state.
  // If only some failed we still render with what we have.
  const errored =
    matchesRes.status === 'rejected' &&
    countsRes.status === 'rejected' &&
    clipsRes.status === 'rejected';

  // Only a fetch that SUCCEEDED can prove its selected id is gone. A failed matches/clips fetch keeps
  // the previous slice, so testing survival against it would wrongly drop a still-present selection —
  // treat a rejected fetch as "still present" so the selection survives untouched.
  const { selectedMatchId, selectedClipId } = prev;
  const matchPresent =
    selectedMatchId !== null &&
    (matchesRes.status === 'rejected' || matches.some((m) => m.id === selectedMatchId));
  const clipPresent =
    selectedClipId !== null &&
    (clipsRes.status === 'rejected' || clips.some((c) => c.id === selectedClipId));
  // A clip selection points selectedMatchId at the clip's parent (which is in the matches list), so a
  // surviving match OR clip keeps the selection alive.
  const stillPresent = matchPresent || clipPresent;

  return {
    matches,
    counts,
    clips,
    loadState: errored ? 'error' : 'ready',
    selectedMatchId: stillPresent ? selectedMatchId : null,
    selectedClipId: clipPresent ? selectedClipId : null,
  };
}
