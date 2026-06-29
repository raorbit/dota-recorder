// Pure, dependency-free logic behind the match table's configurable/sortable columns: the column
// metadata (sort accessors, default visibility, widths/labels), the sort comparator, the per-tab
// (per-bucket) column/sort preference load + sanitize, and the storage-path + formatting helpers.
//
// Deliberately free of React / JSX / CSS so it can be unit-tested in a plain Node environment
// (see match-columns.test.ts). The cell RENDERERS (which return JSX) live in MatchTable.tsx, keyed
// by these columns' `key`; this module is the single source of truth for everything else.
import type { MatchSummary } from '../api/client';
import { heroDisplayName } from '../data/heroes';
import { bucketLabelOf } from '../store/buckets';

// Em-dash shown for any field a row does not yet carry (un-enriched / not in the summary shape).
export const EMDASH = '—';

// Formats `playedAt` (epoch millis) as the mockup's "HH:MM · DD Mon". Returns an em-dash when
// unparseable so an odd value never throws or renders garbage.
export function formatPlayedAt(playedAt: number | null): string {
  if (playedAt === null) return EMDASH;
  const date = new Date(playedAt);
  if (Number.isNaN(date.getTime())) return EMDASH;
  const hh = String(date.getHours()).padStart(2, '0');
  const mm = String(date.getMinutes()).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  const month = date.toLocaleString('en-US', { month: 'short' });
  return `${hh}:${mm} · ${day} ${month}`;
}

// A match duration (seconds) as m:ss, growing to h:mm:ss past an hour. Em-dash on a missing/odd
// value so a seeded/un-enriched row never renders garbage.
export function formatDuration(seconds: number | null): string {
  if (seconds === null || !Number.isFinite(seconds) || seconds < 0) return EMDASH;
  const s = Math.round(seconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = s % 60;
  return h > 0
    ? `${h}:${String(m).padStart(2, '0')}:${String(ss).padStart(2, '0')}`
    : `${m}:${String(ss).padStart(2, '0')}`;
}

// ── Storage-location derivation ───────────────────────────────────────────────
// The storage column shows where a recording lives on disk as a real Windows path: the folder
// containing its .mp4 (e.g. C:\Users\you\Videos\dota-recorder or D:\dota-archive), derived straight
// from the row's videoPath. A pruned row (null videoPath) shows an em-dash.

// The directory portion of a path, with the original separators preserved (so a Windows path stays
// a Windows path). Falls back to the whole string when there's no separator.
export function pathDirname(p: string): string {
  const trimmed = p.replace(/[\\/]+$/, '');
  const idx = Math.max(trimmed.lastIndexOf('\\'), trimmed.lastIndexOf('/'));
  if (idx < 0) return trimmed;
  const dir = trimmed.slice(0, idx);
  // Keep the root separator for a file directly under a drive root (C:\) or filesystem root (\, /),
  // so the folder reads as a real path rather than "C:" or "".
  return dir === '' || dir.endsWith(':') ? dir + trimmed[idx] : dir;
}

export interface StorageInfo {
  // The recording's folder as a Windows path, or an em-dash when the file has been pruned.
  readonly label: string;
  // True when the recording's video has been removed (retention swept the file); these sort last
  // and render muted.
  readonly removed: boolean;
  // Full file path for the cell's title tooltip.
  readonly title: string;
}

export function storageInfoOf(match: MatchSummary): StorageInfo {
  const path = match.videoPath;
  if (path === null || path.trim() === '') {
    return { label: EMDASH, removed: true, title: 'Recording removed' };
  }
  return { label: pathDirname(path), removed: false, title: path };
}

// ── Column model ──────────────────────────────────────────────────────────────
export type ColumnKey =
  | 'hero'
  | 'result'
  | 'kda'
  | 'gpm'
  | 'xpm'
  | 'netWorth'
  | 'lastHits'
  | 'duration'
  | 'mode'
  | 'mmr'
  | 'storage'
  | 'date';

export type SortDir = 'asc' | 'desc';

export interface SortState {
  readonly key: ColumnKey;
  readonly dir: SortDir;
}

// The non-render metadata for a column: identity, layout, default visibility, and the sort accessor.
// The matching cell renderer (JSX) lives in MatchTable.tsx keyed by `key`.
export interface ColumnMeta {
  readonly key: ColumnKey;
  // Uppercase header label, matching the table's display type.
  readonly headerLabel: string;
  // Sentence-case label for the column-picker checkboxes.
  readonly menuLabel: string;
  // CSS grid track size for this column.
  readonly width: string;
  // HERO is always shown and absent from the picker.
  readonly fixed?: boolean;
  // Whether the column is in the base default set (consulted only for non-fixed columns — fixed
  // columns like HERO are always rendered, so this is ignored for them and omitted there).
  readonly defaultVisible?: boolean;
  // Buckets where this column is default-on IN ADDITION to the base set (e.g. MMR only matters for
  // ranked play). Keeps per-tab default logic as data rather than a bucket-name branch.
  readonly defaultBuckets?: readonly string[];
  // Clicking a fresh column starts descending for numeric/date columns (largest/newest first),
  // ascending for text columns.
  readonly descFirst?: boolean;
  // Sort key for this column; null/'' always sorts last regardless of direction.
  readonly sortValue: (m: MatchSummary) => string | number | null;
}

export const COLUMN_META: readonly ColumnMeta[] = [
  {
    key: 'hero',
    headerLabel: 'HERO',
    menuLabel: 'Hero',
    width: '1.6fr',
    fixed: true,
    sortValue: (m) => heroDisplayName(m.hero).toLowerCase(),
  },
  { key: 'result', headerLabel: 'RESULT', menuLabel: 'Result', width: '0.8fr', defaultVisible: true, sortValue: (m) => m.result ?? null },
  { key: 'kda', headerLabel: 'K / D / A', menuLabel: 'K / D / A', width: '0.9fr', defaultVisible: true, descFirst: true, sortValue: (m) => m.kills },
  { key: 'gpm', headerLabel: 'GPM', menuLabel: 'GPM', width: '0.7fr', defaultVisible: true, descFirst: true, sortValue: (m) => m.gpm },
  { key: 'xpm', headerLabel: 'XPM', menuLabel: 'XPM', width: '0.7fr', defaultVisible: false, descFirst: true, sortValue: (m) => m.xpm },
  { key: 'netWorth', headerLabel: 'NET WORTH', menuLabel: 'Net worth', width: '1fr', defaultVisible: false, descFirst: true, sortValue: (m) => m.netWorth },
  { key: 'lastHits', headerLabel: 'LAST HITS', menuLabel: 'Last hits', width: '0.9fr', defaultVisible: false, descFirst: true, sortValue: (m) => m.lastHits },
  { key: 'duration', headerLabel: 'DURATION', menuLabel: 'Duration', width: '0.8fr', defaultVisible: false, descFirst: true, sortValue: (m) => m.durationS },
  { key: 'mode', headerLabel: 'MODE', menuLabel: 'Mode', width: '0.9fr', defaultVisible: true, sortValue: (m) => bucketLabelOf(m).toLowerCase() },
  {
    key: 'mmr',
    headerLabel: 'MMR',
    menuLabel: 'MMR',
    width: '0.7fr',
    // Ranked-only stat: not in the base default set, default-on only for the Ranked tab. Still
    // toggleable on any tab.
    defaultVisible: false,
    defaultBuckets: ['ranked'],
    descFirst: true,
    sortValue: (m) => m.mmrDelta,
  },
  {
    key: 'storage',
    headerLabel: 'STORAGE',
    menuLabel: 'Storage location',
    width: '1.8fr',
    defaultVisible: false,
    sortValue: (m) => {
      const info = storageInfoOf(m);
      return info.removed ? null : info.label.toLowerCase();
    },
  },
  // DATE sorts by playedAt but falls back to createdAt when a row isn't enriched yet (playedAt
  // null), mirroring the core list order (ORDER BY COALESCE(played_at, created_at) DESC). Without the
  // fallback a freshly-recorded row would sink to the bottom of the default date-desc view instead of
  // appearing on top. (Display still uses formatPlayedAt(playedAt), so an un-enriched row shows "—".)
  { key: 'date', headerLabel: 'DATE', menuLabel: 'Date', width: '1fr', defaultVisible: true, descFirst: true, sortValue: (m) => m.playedAt ?? m.createdAt },
];

export const COLUMN_META_BY_KEY = new Map<ColumnKey, ColumnMeta>(COLUMN_META.map((c) => [c.key, c]));
export const DEFAULT_SORT: SortState = { key: 'date', dir: 'desc' };

// localStorage keys for the table's PER-TAB view preferences (each bucket keeps its own column set +
// sort), so a chosen layout survives reloads. Persistence is best-effort: a corrupt/blocked store
// falls back to defaults rather than throwing.
export const COLS_PREF_KEY = 'dotarec.matchTable.columnsByBucket';
export const SORT_PREF_KEY = 'dotarec.matchTable.sortByBucket';

// The base toggleable columns shown by default on every tab (HERO is fixed and not stored).
export const BASE_DEFAULT_KEYS: ColumnKey[] = COLUMN_META.filter(
  (c) => c.defaultVisible && c.fixed !== true,
).map((c) => c.key);

// The default visible columns for a given tab/bucket: the base set plus any column that opts into
// this bucket via its `defaultBuckets` (e.g. MMR on 'ranked'). All per-tab default rules live in the
// column metadata, not here.
export function defaultVisibleKeysFor(bucket: string): ColumnKey[] {
  const extra = COLUMN_META.filter(
    (c) => c.fixed !== true && !c.defaultVisible && c.defaultBuckets?.includes(bucket),
  ).map((c) => c.key);
  return [...BASE_DEFAULT_KEYS, ...extra];
}

// Keep only known, non-fixed column keys so a renamed/removed column in a stale pref can't break
// rendering. Returns null when the value isn't a key array at all.
export function sanitizeKeys(value: unknown): ColumnKey[] | null {
  if (!Array.isArray(value)) return null;
  return value.filter(
    (k): k is ColumnKey =>
      COLUMN_META_BY_KEY.has(k as ColumnKey) && COLUMN_META_BY_KEY.get(k as ColumnKey)?.fixed !== true,
  );
}

// Shared parse for the per-bucket pref maps: read the key, JSON-parse, require a plain object (not an
// array), and keep each entry whose value `parseEntry` accepts (returns non-null). Any failure
// (missing/corrupt/non-object) yields {} so per-bucket defaults apply.
function loadBucketMap<T>(key: string, parseEntry: (value: unknown) => T | null): Record<string, T> {
  try {
    const raw = localStorage.getItem(key);
    if (raw !== null) {
      const parsed = JSON.parse(raw) as unknown;
      if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
        const out: Record<string, T> = {};
        for (const [bucket, value] of Object.entries(parsed as Record<string, unknown>)) {
          const entry = parseEntry(value);
          if (entry !== null) out[bucket] = entry;
        }
        return out;
      }
    }
  } catch {
    /* unreadable/corrupt pref — start empty (defaults apply per bucket) */
  }
  return {};
}

// Per-bucket visible-column map (bucket -> column keys). A missing entry falls back to that bucket's
// default at read time, so only customized tabs are stored.
export function loadVisibleByBucket(): Record<string, ColumnKey[]> {
  return loadBucketMap(COLS_PREF_KEY, sanitizeKeys);
}

// Per-bucket sort map (bucket -> sort state). Invalid entries are dropped.
export function loadSortByBucket(): Record<string, SortState> {
  return loadBucketMap(SORT_PREF_KEY, (value) => {
    const sv = value as { key?: unknown; dir?: unknown };
    return COLUMN_META_BY_KEY.has(sv.key as ColumnKey) && (sv.dir === 'asc' || sv.dir === 'desc')
      ? { key: sv.key as ColumnKey, dir: sv.dir }
      : null;
  });
}

// Core 3-way compare over already-extracted sort values. Null/blank sorts last in BOTH directions;
// ties fall back to newest-id-first (stable, direction-independent).
function compareValues(
  va: string | number | null,
  vb: string | number | null,
  dir: SortDir,
  aId: number,
  bId: number,
): number {
  const aEmpty = va === null || va === undefined || va === '';
  const bEmpty = vb === null || vb === undefined || vb === '';
  if (aEmpty && bEmpty) return bId - aId;
  if (aEmpty) return 1;
  if (bEmpty) return -1;
  let cmp: number;
  if (typeof va === 'number' && typeof vb === 'number') {
    cmp = va - vb;
  } else {
    cmp = String(va).localeCompare(String(vb));
  }
  if (cmp !== 0) return dir === 'asc' ? cmp : -cmp;
  return bId - aId; // stable tiebreak, independent of direction
}

// Compare two matches by a column's sort value. Re-derives the value per call — fine for spot
// comparisons; for bulk sorting use sortMatches (derives each key once).
export function compareMatches(
  a: MatchSummary,
  b: MatchSummary,
  col: ColumnMeta,
  dir: SortDir,
): number {
  return compareValues(col.sortValue(a), col.sortValue(b), dir, a.id, b.id);
}

// Sort matches by a column, deriving each row's sort key exactly once (decorate-sort-undecorate)
// rather than recomputing it on every O(N log N) comparison. Does not mutate the input array.
export function sortMatches(
  rows: readonly MatchSummary[],
  col: ColumnMeta,
  dir: SortDir,
): MatchSummary[] {
  return rows
    .map((m) => ({ m, key: col.sortValue(m) }))
    .sort((x, y) => compareValues(x.key, y.key, dir, x.m.id, y.m.id))
    .map((d) => d.m);
}
