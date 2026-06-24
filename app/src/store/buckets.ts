import type { MatchSummary } from '../api/client';

// The seven library buckets the sidebar lists. `unsorted` is the holding pen for
// recorded-but-not-yet-enriched matches: a row never defaults into `ranked`.
export type Bucket =
  | 'ranked'
  | 'unranked'
  | 'turbo'
  | 'abilityDraft'
  | 'manual'
  | 'clips'
  | 'unsorted';

export const BUCKET_LABELS: Record<Bucket, string> = {
  ranked: 'Ranked',
  unranked: 'Unranked',
  turbo: 'Turbo',
  abilityDraft: 'Ability Draft',
  manual: 'Manual',
  clips: 'Clips',
  unsorted: 'Unsorted',
};

function isRanked(match: MatchSummary): boolean {
  return match.enrichmentState === 'enriched' && match.lobbyType === 7;
}

function isUnranked(match: MatchSummary): boolean {
  // `x !== 7` is already true when x is null, so the null checks the core's SQL needs
  // (`lobby_type IS NULL OR ...`) are implicit in JS — no explicit null guard required.
  return (
    match.enrichmentState === 'enriched' &&
    match.recordKind === 'match' &&
    match.lobbyType !== 7 &&
    match.gameMode !== 23 &&
    match.gameMode !== 18
  );
}

function isTurbo(match: MatchSummary): boolean {
  return match.gameMode === 23;
}

function isAbilityDraft(match: MatchSummary): boolean {
  return match.gameMode === 18;
}

function isManual(match: MatchSummary): boolean {
  return match.recordKind === 'manual';
}

function isClips(match: MatchSummary): boolean {
  return match.recordKind === 'clip';
}

function isUnsorted(match: MatchSummary): boolean {
  return (
    match.recordKind === 'match' &&
    (match.enrichmentState === 'pending' ||
      match.enrichmentState === 'failed' ||
      match.enrichmentState === 'gsi_only')
  );
}

// Mirrors core dev.dotarec.data.Bucket predicates. The predicates are evaluated
// in core enum order; unknown/future rows fall back to Unsorted for browse safety.
export function bucketOf(match: MatchSummary): Bucket {
  if (isRanked(match)) return 'ranked';
  if (isUnranked(match)) return 'unranked';
  if (isTurbo(match)) return 'turbo';
  if (isAbilityDraft(match)) return 'abilityDraft';
  if (isManual(match)) return 'manual';
  if (isClips(match)) return 'clips';
  if (isUnsorted(match)) return 'unsorted';
  return 'unsorted';
}

export function bucketLabelOf(match: MatchSummary): string {
  return BUCKET_LABELS[bucketOf(match)];
}

const BUCKET_PREDICATES: Record<Bucket, (match: MatchSummary) => boolean> = {
  ranked: isRanked,
  unranked: isUnranked,
  turbo: isTurbo,
  abilityDraft: isAbilityDraft,
  manual: isManual,
  clips: isClips,
  unsorted: isUnsorted,
};

// Membership test for a single bucket. Unlike `bucketOf` (which assigns each row to
// exactly ONE bucket for its display label, first-match-wins), this mirrors the core
// Bucket predicates, which are INDEPENDENT — a row can belong to several buckets (e.g.
// an enriched ranked Turbo match counts under both `ranked` and `turbo`). The match LIST
// must filter with this so it agrees with the server-computed sidebar counts; otherwise
// a row visible under one bucket would be missing from another it's also counted in.
export function matchesBucket(match: MatchSummary, bucket: Bucket): boolean {
  return BUCKET_PREDICATES[bucket](match);
}
