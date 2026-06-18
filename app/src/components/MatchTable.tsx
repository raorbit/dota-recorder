import { useMemo } from 'react';
import { useLibraryStore, type Bucket, type ResultFilter } from '../store/library';
import type { MatchSummary } from '../api/client';
import './match-table.css';

// Em-dash shown for any field a row does not yet carry (un-enriched / not in the
// summary shape). Rendered muted, never as a real value.
const EMDASH = '—';

// Maps a match's `category` string to one of the seven library buckets. Anything
// unrecognized falls into `unsorted` — a recorded-but-not-enriched match is NEVER
// silently treated as ranked.
function bucketOf(match: MatchSummary): Bucket {
  switch (match.category) {
    case 'ranked':
      return 'ranked';
    case 'unranked':
      return 'unranked';
    case 'turbo':
      return 'turbo';
    case 'ability_draft':
    case 'abilityDraft':
      return 'abilityDraft';
    case 'manual':
      return 'manual';
    case 'clips':
    case 'clip':
      return 'clips';
    default:
      return 'unsorted';
  }
}

function matchesResultFilter(match: MatchSummary, filter: ResultFilter): boolean {
  if (filter === 'all') return true;
  if (filter === 'wins') return match.result === 'win';
  return match.result === 'loss';
}

function matchesSearch(match: MatchSummary, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (q === '') return true;
  return (
    (match.hero ?? '').toLowerCase().includes(q) ||
    match.category.toLowerCase().includes(q) ||
    String(match.matchId).includes(q)
  );
}

// Formats `playedAt` (ISO string or epoch-millis string) as the mockup's
// "HH:MM · DD Mon". Returns an em-dash when unparseable so an odd value never
// throws or renders garbage.
function formatPlayedAt(playedAt: string): string {
  if (!playedAt) return EMDASH;
  const asNumber = Number(playedAt);
  const date = Number.isFinite(asNumber) && playedAt.trim() !== ''
    ? new Date(asNumber)
    : new Date(playedAt);
  if (Number.isNaN(date.getTime())) return EMDASH;
  const hh = String(date.getHours()).padStart(2, '0');
  const mm = String(date.getMinutes()).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  const month = date.toLocaleString('en-US', { month: 'short' });
  return `${hh}:${mm} · ${day} ${month}`;
}

interface RowProps {
  readonly match: MatchSummary;
  readonly selected: boolean;
  readonly onSelect: (id: number) => void;
}

function MatchRow({ match, selected, onSelect }: RowProps): React.JSX.Element {
  const hasResult = match.result === 'win' || match.result === 'loss';
  const hasKda =
    Number.isFinite(match.kills) &&
    Number.isFinite(match.deaths) &&
    Number.isFinite(match.assists);

  return (
    <button
      type="button"
      className="mt-row"
      data-selected={selected ? 'true' : 'false'}
      onClick={() => onSelect(match.matchId)}
    >
      <div className="mt-cell mt-hero">
        <span className="mt-hero-chip" aria-hidden="true" />
        <span className="mt-hero-name">{match.hero || 'Unknown hero'}</span>
      </div>

      <div className="mt-cell mt-result" data-result={hasResult ? match.result : 'none'}>
        {hasResult ? match.result.toUpperCase() : EMDASH}
      </div>

      <div className="mt-cell mt-mono">
        {hasKda ? `${match.kills} / ${match.deaths} / ${match.assists}` : EMDASH}
      </div>

      {/* GPM / MODE / MMR are not in the list summary; they arrive with enrichment.
          Until then they read as muted em-dash — the un-enriched presentation. */}
      <div className="mt-cell mt-mono mt-muted">{EMDASH}</div>
      <div className="mt-cell mt-mode">{EMDASH}</div>
      <div className="mt-cell mt-mono mt-muted">{EMDASH}</div>

      <div className="mt-cell mt-date">{formatPlayedAt(match.playedAt)}</div>
    </button>
  );
}

// The 7-column match table: a sticky header + rows, with loading / empty /
// error states. Filtering (bucket, win/loss, search) happens here against the
// store's match list. Selecting a row sets selectedMatchId (the player reads it).
export function MatchTable(): React.JSX.Element {
  const matches = useLibraryStore((s) => s.matches);
  const bucket = useLibraryStore((s) => s.bucket);
  const resultFilter = useLibraryStore((s) => s.resultFilter);
  const search = useLibraryStore((s) => s.search);
  const loadState = useLibraryStore((s) => s.loadState);
  const selectedMatchId = useLibraryStore((s) => s.selectedMatchId);
  const selectMatch = useLibraryStore((s) => s.selectMatch);

  const visible = useMemo(
    () =>
      matches.filter(
        (m) =>
          bucketOf(m) === bucket &&
          matchesResultFilter(m, resultFilter) &&
          matchesSearch(m, search),
      ),
    [matches, bucket, resultFilter, search],
  );

  return (
    <div className="mt-root">
      <div className="mt-header">
        <div>HERO</div>
        <div>RESULT</div>
        <div>K / D / A</div>
        <div>GPM</div>
        <div>MODE</div>
        <div>MMR</div>
        <div>DATE</div>
      </div>

      <div className="mt-body">
        {loadState === 'loading' && (
          <div className="mt-state">Loading recordings…</div>
        )}

        {loadState === 'error' && (
          <div className="mt-state mt-state-error">
            Could not reach the recorder core. Is it running?
          </div>
        )}

        {loadState === 'ready' && visible.length === 0 && (
          <div className="mt-empty">
            <div className="mt-empty-title">No recordings here yet</div>
            <div className="mt-empty-sub">
              {search.trim() !== '' || resultFilter !== 'all'
                ? 'No matches fit the current filters.'
                : 'Recordings appear automatically once you play a Dota 2 match with GSI and OBS connected.'}
            </div>
          </div>
        )}

        {loadState === 'ready' &&
          visible.map((m) => (
            <MatchRow
              key={m.matchId}
              match={m}
              selected={m.matchId === selectedMatchId}
              onSelect={selectMatch}
            />
          ))}
      </div>
    </div>
  );
}
