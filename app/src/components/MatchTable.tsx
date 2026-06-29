import { useMemo, useState } from 'react';
import { useLibraryStore, type ResultFilter } from '../store/library';
import { bucketLabelOf, matchesBucket } from '../store/buckets';
import { heroDisplayName, heroIconUrl } from '../data/heroes';
import { clipThumbUrl, type MatchSummary, type Clip } from '../api/client';
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
    heroDisplayName(match.hero).toLowerCase().includes(q) ||
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
  readonly onToggleStar: (id: number, starred: boolean) => void;
}

// Hero portrait with graceful fallback: shows the CDN icon, degrading to the plain chip
// placeholder when there's no hero or the image can't load (e.g. offline / unknown hero).
function HeroIcon({ hero }: { readonly hero: string | null }): React.JSX.Element {
  const url = heroIconUrl(hero);
  const [failed, setFailed] = useState(false);
  if (url === null || failed) {
    return <span className="mt-hero-chip" aria-hidden="true" />;
  }
  return (
    <img
      className="mt-hero-icon"
      src={url}
      alt=""
      aria-hidden="true"
      loading="lazy"
      onError={() => setFailed(true)}
    />
  );
}

function MatchRow({ match, selected, onSelect, onToggleStar }: RowProps): React.JSX.Element {
  const hasResult = match.result === 'win' || match.result === 'loss';
  const hasKda =
    match.kills !== null && match.deaths !== null && match.assists !== null;

  // The row is a clickable div (not a <button>) so it can hold the real star <button>
  // without nesting interactive elements; Enter/Space still selects for keyboard users.
  return (
    <div
      className="mt-row"
      data-selected={selected ? 'true' : 'false'}
      role="button"
      tabIndex={0}
      onClick={() => onSelect(match.id)}
      onKeyDown={(e) => {
        // Only the row's OWN Enter/Space selects. Without this guard, activating the nested star
        // button by keyboard bubbles here too — toggling the star AND selecting the row in one press.
        if (e.target !== e.currentTarget) return;
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onSelect(match.id);
        }
      }}
    >
      <div className="mt-cell mt-hero">
        <button
          type="button"
          className="mt-star"
          data-on={match.starred ? 'true' : 'false'}
          aria-pressed={match.starred}
          aria-label={match.starred ? 'Unstar recording' : 'Star recording to keep it'}
          title={match.starred ? 'Starred — kept from auto-delete' : 'Star to keep from auto-delete'}
          onClick={(e) => {
            e.stopPropagation();
            onToggleStar(match.id, !match.starred);
          }}
        >
          {match.starred ? '★' : '☆'}
        </button>
        <HeroIcon hero={match.hero} />
        <span className="mt-hero-name">{heroDisplayName(match.hero)}</span>
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
    </div>
  );
}

// A clip's display label: its explicit `label`, else a kind-derived fallback
// ("Rampage" for an auto/triggered clip, "Manual" otherwise). Mirrors the
// VideoPlayer's clipLabel so the bucket list and the player strip read the same.
function clipLabel(clip: Clip): string {
  if (clip.label != null && clip.label.trim() !== '') return clip.label;
  if (clip.kind === 'auto') {
    return clip.triggerReason === 'rampage' ? 'Rampage' : (clip.triggerReason ?? 'Auto');
  }
  return 'Manual';
}

function clipMatchesSearch(clip: Clip, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (q === '') return true;
  return (
    clipLabel(clip).toLowerCase().includes(q) ||
    String(clip.id).includes(q) ||
    String(clip.parentMatchId).includes(q)
  );
}

// Thumbnail for a clip row: the generated frame grab, degrading to the plain chip
// placeholder when there's none yet (pending/failed) or the image can't load.
function ClipThumb({ clip }: { readonly clip: Clip }): React.JSX.Element {
  const [failed, setFailed] = useState(false);
  if (clip.thumbPath == null || clip.thumbPath.trim() === '' || failed) {
    return <span className="mt-hero-chip" aria-hidden="true" />;
  }
  return (
    <img
      className="mt-hero-icon"
      src={clipThumbUrl(clip.id)}
      alt=""
      aria-hidden="true"
      loading="lazy"
      onError={() => setFailed(true)}
    />
  );
}

interface ClipRowProps {
  readonly clip: Clip;
  readonly selected: boolean;
  readonly onSelect: (clip: Clip) => void;
}

function ClipRow({ clip, selected, onSelect }: ClipRowProps): React.JSX.Element {
  return (
    <div
      className="mt-row mt-clip-row"
      data-selected={selected ? 'true' : 'false'}
      role="button"
      tabIndex={0}
      onClick={() => onSelect(clip)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onSelect(clip);
        }
      }}
    >
      <div className="mt-cell mt-hero">
        <ClipThumb clip={clip} />
        <span className="mt-hero-name">{clipLabel(clip)}</span>
      </div>
      <div className="mt-cell mt-mono">
        {Math.max(0, Math.round(clip.endOffsetS - clip.startOffsetS))}s
      </div>
      <div className="mt-cell mt-mode">{clip.kind === 'auto' ? 'Auto' : 'Manual'}</div>
      <div className="mt-cell mt-date">{formatPlayedAt(clip.createdAt)}</div>
    </div>
  );
}

// The Clips bucket view: a flat list of every saved clip (from the clips table, not
// the matches list). Clicking a clip opens its parent match in the player and
// auto-plays that clip (selectClip). Mirrors the match table's load/empty states.
function ClipTable(): React.JSX.Element {
  const clips = useLibraryStore((s) => s.clips);
  const search = useLibraryStore((s) => s.search);
  const loadState = useLibraryStore((s) => s.loadState);
  const selectedClipId = useLibraryStore((s) => s.selectedClipId);
  const selectClip = useLibraryStore((s) => s.selectClip);

  const visible = useMemo(
    () => clips.filter((c) => clipMatchesSearch(c, search)),
    [clips, search],
  );

  return (
    <div className="mt-root">
      <div className="mt-header mt-clip-header">
        <div>CLIP</div>
        <div>LENGTH</div>
        <div>KIND</div>
        <div>CREATED</div>
      </div>

      <div className="mt-body">
        {loadState === 'loading' && <div className="mt-state">Loading clips…</div>}

        {loadState === 'error' && (
          <div className="mt-state mt-state-error">
            Could not reach the recorder core. Is it running?
          </div>
        )}

        {loadState === 'ready' && visible.length === 0 && (
          <div className="mt-empty">
            <div className="mt-empty-title">No clips here yet</div>
            <div className="mt-empty-sub">
              {search.trim() !== ''
                ? 'No clips fit the current search.'
                : 'Clip a moment from a recording (the scissors control in the player) and it shows up here.'}
            </div>
          </div>
        )}

        {loadState === 'ready' &&
          visible.map((c) => (
            <ClipRow
              key={c.id}
              clip={c}
              selected={c.id === selectedClipId}
              onSelect={selectClip}
            />
          ))}
      </div>
    </div>
  );
}

// The 7-column match table: a sticky header + rows, with loading / empty /
// error states. Filtering (bucket, win/loss, search) happens here against the
// store's match list. Selecting a row sets selectedMatchId (the player reads it).
// The "Clips" bucket lists clips from their own table instead (see ClipTable).
export function MatchTable(): React.JSX.Element {
  const matches = useLibraryStore((s) => s.matches);
  const bucket = useLibraryStore((s) => s.bucket);
  const resultFilter = useLibraryStore((s) => s.resultFilter);
  const search = useLibraryStore((s) => s.search);
  const loadState = useLibraryStore((s) => s.loadState);
  const selectedMatchId = useLibraryStore((s) => s.selectedMatchId);
  const selectMatch = useLibraryStore((s) => s.selectMatch);
  const toggleStar = useLibraryStore((s) => s.toggleStar);

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

  // Clips live in their own table, not the matches list — render the dedicated
  // clip view for that bucket. (Hooks above still run; the branch is on render only.)
  if (bucket === 'clips') return <ClipTable />;

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
              onToggleStar={(id, starred) => void toggleStar(id, starred)}
            />
          ))}
      </div>
    </div>
  );
}
