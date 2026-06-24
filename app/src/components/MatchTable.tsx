import { useMemo } from 'react';
import { useLibraryStore, type ResultFilter } from '../store/library';
import { bucketLabelOf, matchesBucket } from '../store/buckets';
import type { MatchSummary } from '../api/client';
import './match-table.css';

// Em-dash shown for any field a row does not yet carry (un-enriched / not in the
// summary shape). Rendered muted, never as a real value.
const EMDASH = '—';

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
    bucketLabelOf(match).toLowerCase().includes(q) ||
    String(match.id).includes(q) ||
    (match.dotaMatchId !== null && String(match.dotaMatchId).includes(q))
  );
}

// Formats `playedAt` (epoch millis) as the mockup's
// "HH:MM · DD Mon". Returns an em-dash when unparseable so an odd value never
// throws or renders garbage.
function formatPlayedAt(playedAt: number | null): string {
  if (playedAt === null) return EMDASH;
  const date = new Date(playedAt);
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
    match.kills !== null && match.deaths !== null && match.assists !== null;

  return (
    <button
      type="button"
      className="mt-row"
      data-selected={selected ? 'true' : 'false'}
      onClick={() => onSelect(match.id)}
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

      <div className="mt-cell mt-mono">{match.gpm ?? EMDASH}</div>
      <div className="mt-cell mt-mode">{bucketLabelOf(match)}</div>
      <div className="mt-cell mt-mono">{match.mmrDelta ?? EMDASH}</div>

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
          matchesBucket(m, bucket) &&
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
              key={m.id}
              match={m}
              selected={m.id === selectedMatchId}
              onSelect={selectMatch}
            />
          ))}
      </div>
    </div>
  );
}
