import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useLibraryStore, type ResultFilter } from '../store/library';
import { bucketLabelOf, matchesBucket, BUCKET_LABELS } from '../store/buckets';
import { heroDisplayName, heroIconUrl } from '../data/heroes';
import { clipThumbUrl, type MatchSummary, type Clip } from '../api/client';
import {
  EMDASH,
  formatDuration,
  formatPlayedAt,
  storageInfoOf,
  sortMatches,
  defaultVisibleKeysFor,
  loadVisibleByBucket,
  loadSortByBucket,
  COLUMN_META,
  COLUMN_META_BY_KEY,
  DEFAULT_SORT,
  COLS_PREF_KEY,
  SORT_PREF_KEY,
  type ColumnKey,
  type ColumnMeta,
  type SortState,
} from '../lib/match-columns';
import './match-table.css';

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

// Context threaded to each cell renderer: currently just the star toggle (for the hero
// cell's keep-from-deletion button).
interface CellCtx {
  readonly onToggleStar: (id: number, starred: boolean) => void;
}

// A rendered column = its pure metadata (from match-columns) plus the JSX cell renderer and the
// cell wrapper class. The renderers live here (not in match-columns) because they return JSX / use
// React components; match-columns stays React-free so its logic is unit-testable.
interface ColumnDef extends ColumnMeta {
  readonly cellClass?: string;
  readonly render: (m: MatchSummary, ctx: CellCtx) => React.ReactNode;
}

// Per-column cell renderer + wrapper class, keyed by column. Merged with the pure COLUMN_META
// (sort/layout metadata, from match-columns) below to form the full ColumnDef list the table
// renders. These live here, not in match-columns, because they return JSX / use React components.
const CELL: Record<
  ColumnKey,
  { readonly cellClass?: string; readonly render: (m: MatchSummary, ctx: CellCtx) => React.ReactNode }
> = {
  hero: {
    cellClass: 'mt-hero',
    render: (m, ctx) => (
      <>
        <button
          type="button"
          className="mt-star"
          data-on={m.starred ? 'true' : 'false'}
          aria-pressed={m.starred}
          aria-label={m.starred ? 'Unstar recording' : 'Star recording to keep it'}
          title={m.starred ? 'Starred — kept from auto-delete' : 'Star to keep from auto-delete'}
          onClick={(e) => {
            e.stopPropagation();
            ctx.onToggleStar(m.id, !m.starred);
          }}
        >
          {m.starred ? '★' : '☆'}
        </button>
        <HeroIcon hero={m.hero} />
        <span className="mt-hero-name">{heroDisplayName(m.hero)}</span>
      </>
    ),
  },
  result: {
    render: (m) => {
      const hasResult = m.result === 'win' || m.result === 'loss';
      return (
        <span className="mt-result" data-result={hasResult ? m.result : 'none'}>
          {hasResult ? m.result.toUpperCase() : EMDASH}
        </span>
      );
    },
  },
  kda: {
    cellClass: 'mt-mono',
    render: (m) => {
      const hasKda = m.kills !== null && m.deaths !== null && m.assists !== null;
      return hasKda ? `${m.kills} / ${m.deaths} / ${m.assists}` : EMDASH;
    },
  },
  gpm: { cellClass: 'mt-mono', render: (m) => m.gpm ?? EMDASH },
  xpm: { cellClass: 'mt-mono', render: (m) => m.xpm ?? EMDASH },
  netWorth: {
    cellClass: 'mt-mono',
    render: (m) => (m.netWorth !== null ? m.netWorth.toLocaleString() : EMDASH),
  },
  lastHits: { cellClass: 'mt-mono', render: (m) => m.lastHits ?? EMDASH },
  duration: { cellClass: 'mt-mono', render: (m) => formatDuration(m.durationS) },
  mode: { cellClass: 'mt-mode', render: (m) => bucketLabelOf(m) },
  mmr: { cellClass: 'mt-mono', render: (m) => m.mmrDelta ?? EMDASH },
  storage: {
    cellClass: 'mt-storage',
    render: (m) => {
      const info = storageInfoOf(m);
      return (
        <span className={info.removed ? 'mt-muted' : undefined} title={info.title}>
          {info.label}
        </span>
      );
    },
  },
  date: { cellClass: 'mt-date', render: (m) => formatPlayedAt(m.playedAt) },
};

// The full column list the table renders: pure metadata + the matching cell renderer per key.
// (Per-key lookups use the imported COLUMN_META_BY_KEY — sort only needs ColumnMeta, not renderers.)
const ALL_COLUMNS: readonly ColumnDef[] = COLUMN_META.map((meta) => ({ ...meta, ...CELL[meta.key] }));

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

// ── Popup menu primitive ──────────────────────────────────────────────────────
// A small floating menu rendered into document.body (the library Main column has
// overflow:hidden, which would otherwise clip it). A transparent backdrop catches a
// click-away; Escape closes; the panel is clamped to stay within the viewport. Keyboard
// operable per the ARIA menu pattern: focus moves into the panel on open, Up/Down/Home/End
// move between items, Tab is trapped within the panel, and focus returns to the opener on
// close. `role` is 'menu' for the action menu (menuitem children) or 'group' for the column
// picker (a labelled checkbox group), so the role always matches the actual content.
interface PopupMenuProps {
  readonly x: number;
  readonly y: number;
  readonly onClose: () => void;
  readonly ariaLabel: string;
  readonly role?: 'menu' | 'group';
  readonly children: React.ReactNode;
}

// The focusable, operable items inside a popup panel, in DOM order (menuitem buttons for
// the action menu; checkboxes for the column picker).
function popupItems(panel: HTMLElement | null): HTMLElement[] {
  if (!panel) return [];
  // Exactly the two popup item kinds: action-menu items and column-picker checkboxes. Kept narrow so
  // an incidental future child (a stray button / text input) can't slip into the roving-focus ring.
  return Array.from(panel.querySelectorAll<HTMLElement>('[role="menuitem"], input[type="checkbox"]'));
}

function PopupMenu({ x, y, onClose, ariaLabel, role = 'menu', children }: PopupMenuProps): React.JSX.Element {
  const panelRef = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState({ x, y });

  // Clamp into the viewport once the panel's real size is known.
  useLayoutEffect(() => {
    const el = panelRef.current;
    if (!el) return;
    const { width, height } = el.getBoundingClientRect();
    const margin = 8;
    let nx = x;
    let ny = y;
    if (x + width > window.innerWidth - margin) nx = Math.max(margin, window.innerWidth - width - margin);
    if (y + height > window.innerHeight - margin) ny = Math.max(margin, window.innerHeight - height - margin);
    setPos({ x: nx, y: ny });
  }, [x, y]);

  // Move focus into the menu on open and restore it to the opener (the row / Columns button)
  // on close, so a keyboard user can operate the menu and lands back where they were.
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    popupItems(panelRef.current)[0]?.focus();
    return () => opener?.focus?.();
  }, []);

  // Escape closes from anywhere (a window listener, so it works even before focus lands).
  useEffect(() => {
    const onKey = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  // Roving focus + Tab trap within the panel (Escape is handled by the window listener above).
  const onKeyDown = (e: React.KeyboardEvent): void => {
    const items = popupItems(panelRef.current);
    if (items.length === 0) return;
    const idx = items.indexOf(document.activeElement as HTMLElement);
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      items[(idx + 1 + items.length) % items.length]?.focus();
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      items[(idx - 1 + items.length) % items.length]?.focus();
    } else if (e.key === 'Home') {
      e.preventDefault();
      items[0]?.focus();
    } else if (e.key === 'End') {
      e.preventDefault();
      items[items.length - 1]?.focus();
    } else if (e.key === 'Tab') {
      e.preventDefault();
      const next = e.shiftKey ? (idx - 1 + items.length) % items.length : (idx + 1) % items.length;
      items[next]?.focus();
    }
  };

  return createPortal(
    <div
      className="ctx-backdrop"
      onMouseDown={onClose}
      onContextMenu={(e) => {
        e.preventDefault();
        onClose();
      }}
    >
      <div
        ref={panelRef}
        className="ctx-menu"
        role={role}
        aria-label={ariaLabel}
        style={{ left: pos.x, top: pos.y }}
        onMouseDown={(e) => e.stopPropagation()}
        onKeyDown={onKeyDown}
      >
        {children}
      </div>
    </div>,
    document.body,
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
  readonly onToggleStar: (id: number, starred: boolean) => void;
}

function ClipRow({ clip, selected, onSelect, onToggleStar }: ClipRowProps): React.JSX.Element {
  return (
    <div
      className="mt-row mt-clip-row"
      data-selected={selected ? 'true' : 'false'}
      role="button"
      tabIndex={0}
      onClick={() => onSelect(clip)}
      onKeyDown={(e) => {
        // Only the row's OWN Enter/Space selects — not a bubble from the nested star button.
        if (e.target !== e.currentTarget) return;
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onSelect(clip);
        }
      }}
    >
      <div className="mt-cell mt-hero">
        <button
          type="button"
          className="mt-star"
          data-on={clip.starred ? 'true' : 'false'}
          aria-pressed={clip.starred}
          aria-label={clip.starred ? 'Unstar clip' : 'Star clip to keep it'}
          title={clip.starred ? 'Starred — kept from auto-delete' : 'Star to keep from auto-delete'}
          onClick={(e) => {
            e.stopPropagation();
            onToggleStar(clip.id, !clip.starred);
          }}
        >
          {clip.starred ? '★' : '☆'}
        </button>
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
  const toggleClipStar = useLibraryStore((s) => s.toggleClipStar);

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
              onToggleStar={(id, starred) => void toggleClipStar(id, starred)}
            />
          ))}
      </div>
    </div>
  );
}

interface MatchRowProps {
  readonly match: MatchSummary;
  readonly columns: readonly ColumnDef[];
  readonly gridTemplate: string;
  readonly selected: boolean;
  readonly ctx: CellCtx;
  readonly onSelect: (id: number, opts?: { readonly shift?: boolean; readonly toggle?: boolean }) => void;
  readonly onOpenMenu: (match: MatchSummary, x: number, y: number) => void;
}

function MatchRow({
  match,
  columns,
  gridTemplate,
  selected,
  ctx,
  onSelect,
  onOpenMenu,
}: MatchRowProps): React.JSX.Element {
  // The row is a clickable div (not a <button>) so it can hold the real star <button>
  // without nesting interactive elements; Enter/Space still selects for keyboard users.
  return (
    <div
      className="mt-row"
      style={{ gridTemplateColumns: gridTemplate }}
      data-selected={selected ? 'true' : 'false'}
      role="button"
      tabIndex={0}
      onClick={(e) => onSelect(match.id, { shift: e.shiftKey, toggle: e.ctrlKey || e.metaKey })}
      onContextMenu={(e) => {
        e.preventDefault();
        onOpenMenu(match, e.clientX, e.clientY);
      }}
      onKeyDown={(e) => {
        // Only the row's OWN keys act. Without this guard, activating the nested star button
        // by keyboard bubbles here too — toggling the star AND selecting the row in one press.
        if (e.target !== e.currentTarget) return;
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onSelect(match.id);
        } else if (e.key === 'ContextMenu' || (e.shiftKey && e.key === 'F10')) {
          // Keyboard equivalent of right-click: open the actions menu anchored to the row.
          e.preventDefault();
          const r = e.currentTarget.getBoundingClientRect();
          onOpenMenu(match, r.left + 16, r.top + r.height - 6);
        }
      }}
    >
      {columns.map((col) => (
        <div key={col.key} className={col.cellClass ? `mt-cell ${col.cellClass}` : 'mt-cell'}>
          {col.render(match, ctx)}
        </div>
      ))}
    </div>
  );
}

// The match table: a configurable, sortable set of columns over a sticky header + rows,
// with loading / empty / error states. Filtering (bucket, win/loss, search) happens here
// against the store's match list; clicking a header sorts; a "Columns" button toggles
// which columns show (incl. storage location); right-clicking a row opens an actions menu.
// Selecting a row sets selectedMatchId (the player reads it). The "Clips" bucket lists
// clips from their own table instead (see ClipTable).
export function MatchTable(): React.JSX.Element {
  const matches = useLibraryStore((s) => s.matches);
  const bucket = useLibraryStore((s) => s.bucket);
  const resultFilter = useLibraryStore((s) => s.resultFilter);
  const search = useLibraryStore((s) => s.search);
  const loadState = useLibraryStore((s) => s.loadState);
  const selectedMatchId = useLibraryStore((s) => s.selectedMatchId);
  const selectMatch = useLibraryStore((s) => s.selectMatch);
  const toggleStar = useLibraryStore((s) => s.toggleStar);
  const deleteMatch = useLibraryStore((s) => s.deleteMatch);
  const deleteMatches = useLibraryStore((s) => s.deleteMatches);

  // Multi-selection for bulk row actions: shift-click = range from the anchor, Ctrl/Cmd-click =
  // toggle one. LOCAL to the table (the player only cares about the single selectedMatchId). A plain
  // click resets this to the clicked row AND opens it in the player; shift/Ctrl build the set without
  // opening. The right-click menu then acts on the whole set.
  const [selectedIds, setSelectedIds] = useState<ReadonlySet<number>>(() => new Set<number>());
  const anchorRef = useRef<number | null>(null);

  // View preferences (persisted), kept PER TAB/bucket so each tab has its own columns + sort.
  const [visibleByBucket, setVisibleByBucket] =
    useState<Record<string, ColumnKey[]>>(loadVisibleByBucket);
  const [sortByBucket, setSortByBucket] = useState<Record<string, SortState>>(loadSortByBucket);
  // The current tab's effective columns + sort: its saved entry, else that tab's defaults.
  const visibleKeys = useMemo(
    () => visibleByBucket[bucket] ?? defaultVisibleKeysFor(bucket),
    [visibleByBucket, bucket],
  );
  const sort = useMemo(() => sortByBucket[bucket] ?? DEFAULT_SORT, [sortByBucket, bucket]);

  // The column-picker dropdown anchor (null = closed) and the row context menu state.
  const [colsMenuAt, setColsMenuAt] = useState<{ x: number; y: number } | null>(null);
  const [rowMenu, setRowMenu] = useState<{ match: MatchSummary; x: number; y: number } | null>(null);
  // Two-step delete inside the row menu: the first "Delete" click arms the confirm so a
  // permanent delete can't fire on a single accidental click.
  const [menuDeleteArmed, setMenuDeleteArmed] = useState(false);

  // Skip the persistence writes on the initial mount (they'd just rewrite the values we just
  // loaded); persist only after a real user edit. hydratedRef is flipped true by the effect below,
  // which runs AFTER these two on mount (effects fire in declaration order).
  const hydratedRef = useRef(false);

  useEffect(() => {
    if (!hydratedRef.current) return;
    try {
      localStorage.setItem(COLS_PREF_KEY, JSON.stringify(visibleByBucket));
    } catch {
      /* persistence is best-effort */
    }
  }, [visibleByBucket]);

  useEffect(() => {
    if (!hydratedRef.current) return;
    try {
      localStorage.setItem(SORT_PREF_KEY, JSON.stringify(sortByBucket));
    } catch {
      /* persistence is best-effort */
    }
  }, [sortByBucket]);

  useEffect(() => {
    hydratedRef.current = true;
  }, []);

  // Switching tabs clears the multi-selection so a bulk action can't reach rows from another bucket.
  useEffect(() => {
    setSelectedIds(new Set<number>());
    anchorRef.current = null;
  }, [bucket]);

  const visibleSet = useMemo(() => new Set(visibleKeys), [visibleKeys]);
  // Render columns in the canonical ALL_COLUMNS order (stable regardless of toggle order);
  // HERO is always present.
  const columns = useMemo(
    () => ALL_COLUMNS.filter((c) => c.fixed === true || visibleSet.has(c.key)),
    [visibleSet],
  );
  const gridTemplate = useMemo(() => columns.map((c) => c.width).join(' '), [columns]);

  const visible = useMemo(() => {
    const filtered = matches.filter(
      (m) =>
        matchesBucket(m, bucket) &&
        matchesResultFilter(m, resultFilter) &&
        matchesSearch(m, search),
    );
    const col = COLUMN_META_BY_KEY.get(sort.key) ?? COLUMN_META_BY_KEY.get('date')!;
    return sortMatches(filtered, col, sort.dir);
  }, [matches, bucket, resultFilter, search, sort]);

  const cellCtx = useMemo<CellCtx>(
    () => ({ onToggleStar: (id, starred) => void toggleStar(id, starred) }),
    [toggleStar],
  );

  // Look up a match by id for the bulk menu actions (reveal / copy id over the whole selection).
  const matchById = useMemo(() => new Map(matches.map((m) => [m.id, m])), [matches]);

  // Clips live in their own table, not the matches list — render the dedicated
  // clip view for that bucket. (Hooks above still run; the branch is on render only.)
  if (bucket === 'clips') return <ClipTable />;

  // Row selection. shift = range from the anchor over the CURRENT visible order; Ctrl/Cmd = toggle
  // one; plain = single-select AND open in the player (the existing behaviour). Only a plain click
  // touches the player; shift/Ctrl just build the set for the right-click bulk menu.
  const onRowSelect = (id: number, opts?: { shift?: boolean; toggle?: boolean }): void => {
    if (opts?.shift && anchorRef.current !== null) {
      const ids = visible.map((m) => m.id);
      const a = ids.indexOf(anchorRef.current);
      const b = ids.indexOf(id);
      if (a === -1 || b === -1) {
        setSelectedIds(new Set([id]));
        anchorRef.current = id;
      } else {
        const [lo, hi] = a <= b ? [a, b] : [b, a];
        setSelectedIds(new Set(ids.slice(lo, hi + 1))); // anchor stays so the range keeps its origin
      }
      return;
    }
    if (opts?.toggle) {
      setSelectedIds((prev) => {
        const next = new Set(prev);
        if (next.has(id)) next.delete(id);
        else next.add(id);
        return next;
      });
      anchorRef.current = id;
      return;
    }
    setSelectedIds(new Set([id]));
    anchorRef.current = id;
    selectMatch(id);
  };

  // Highlight rows in the multi-selection; with none active, fall back to the player's single
  // selectedMatchId so the open match stays highlighted exactly as before.
  const isRowSelected = (id: number): boolean =>
    selectedIds.size > 0 ? selectedIds.has(id) : id === selectedMatchId;

  // Sort/columns edits apply to the CURRENT tab only (keyed by bucket), materializing that tab's
  // defaults first so a never-customized tab edits from its real starting point.
  const onSortBy = (key: ColumnKey): void => {
    setSortByBucket((prev) => {
      const current = prev[bucket] ?? DEFAULT_SORT;
      const next: SortState =
        current.key === key
          ? { key, dir: current.dir === 'asc' ? 'desc' : 'asc' }
          : { key, dir: COLUMN_META_BY_KEY.get(key)?.descFirst ? 'desc' : 'asc' };
      return { ...prev, [bucket]: next };
    });
  };

  const toggleColumn = (key: ColumnKey): void => {
    const removing = visibleKeys.includes(key);
    setVisibleByBucket((prev) => {
      const current = prev[bucket] ?? defaultVisibleKeysFor(bucket);
      const next = current.includes(key)
        ? current.filter((k) => k !== key)
        : [...current, key];
      return { ...prev, [bucket]: next };
    });
    // Hiding the column this tab is sorted by would leave the list ordered by an invisible column
    // with no header/caret to change it — fall back to the default sort in that case.
    if (removing && sort.key === key) {
      setSortByBucket((prev) => ({ ...prev, [bucket]: DEFAULT_SORT }));
    }
  };

  const openColumnsMenu = (e: React.MouseEvent<HTMLButtonElement>): void => {
    const rect = e.currentTarget.getBoundingClientRect();
    setColsMenuAt({ x: rect.left, y: rect.bottom + 4 });
  };

  const openRowMenu = (match: MatchSummary, x: number, y: number): void => {
    setMenuDeleteArmed(false);
    // Right-clicking a row OUTSIDE the current multi-selection collapses the selection to just that
    // row, so the menu acts on what was clicked. Right-clicking a row that IS in the selection keeps
    // it, so the menu acts on the whole set.
    setSelectedIds((prev) => (prev.has(match.id) ? prev : new Set([match.id])));
    setRowMenu({ match, x, y });
  };

  const closeRowMenu = (): void => {
    setRowMenu(null);
    setMenuDeleteArmed(false);
  };

  // Reveal is only available inside Electron (the preload exposes it) and only when the row still
  // carries a videoPath. The main process additionally verifies the file exists on disk before
  // revealing (see the shell:revealPath handler), so a row whose file was swept is a harmless no-op.
  const canReveal = (match: MatchSummary): boolean =>
    typeof window.dotarec?.revealPath === 'function' &&
    match.videoPath !== null &&
    match.videoPath.trim() !== '';

  const copyMatchId = (match: MatchSummary): void => {
    const id = String(match.dotaMatchId ?? match.id);
    // writeText can reject async (denied permission / document not focused); swallow it so a
    // failed copy is a silent no-op, not an unhandled rejection. The optional chain also
    // short-circuits the .catch when clipboard is absent (non-secure context).
    void navigator.clipboard?.writeText(id).catch(() => {});
  };

  return (
    <div className="mt-root">
      <div className="mt-toolbar">
        <button
          type="button"
          className="mt-cols-btn"
          // The popup is a role="group" of checkboxes, not a menu/listbox/dialog — none of the
          // aria-haspopup token values fit (and "true" is spec-equivalent to "menu"), so we omit
          // it rather than mis-announce the popup type. aria-expanded still conveys open/closed.
          aria-expanded={colsMenuAt !== null}
          onClick={openColumnsMenu}
        >
          <span aria-hidden="true">⚙</span> Columns
        </button>
      </div>

      <div className="mt-header" style={{ gridTemplateColumns: gridTemplate }}>
        {columns.map((col) => {
          const active = sort.key === col.key;
          return (
            <button
              key={col.key}
              type="button"
              className="mt-th"
              data-active={active ? 'true' : 'false'}
              // Fold the live sort state into the accessible name — the caret is aria-hidden and
              // there's no columnheader/aria-sort here (this is a CSS-grid pseudo-table), so the
              // name is the only place a screen reader learns the active column + direction.
              aria-label={
                active
                  ? `${col.menuLabel}, sorted ${sort.dir === 'asc' ? 'ascending' : 'descending'}; activate to reverse`
                  : `Sort by ${col.menuLabel}`
              }
              onClick={() => onSortBy(col.key)}
            >
              <span className="mt-th-label">{col.headerLabel}</span>
              <span className="mt-sort-caret" aria-hidden="true">
                {active ? (sort.dir === 'asc' ? '▲' : '▼') : ''}
              </span>
            </button>
          );
        })}
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
              columns={columns}
              gridTemplate={gridTemplate}
              selected={isRowSelected(m.id)}
              ctx={cellCtx}
              onSelect={onRowSelect}
              onOpenMenu={openRowMenu}
            />
          ))}
      </div>

      {colsMenuAt && (
        <PopupMenu
          x={colsMenuAt.x}
          y={colsMenuAt.y}
          onClose={() => setColsMenuAt(null)}
          ariaLabel={`Choose columns for ${BUCKET_LABELS[bucket]}`}
          role="group"
        >
          <div className="ctx-menu-head" aria-hidden="true">
            COLUMNS · {BUCKET_LABELS[bucket]}
          </div>
          {ALL_COLUMNS.filter((c) => c.fixed !== true).map((c) => (
            <label key={c.key} className="ctx-check">
              <input
                type="checkbox"
                checked={visibleSet.has(c.key)}
                onChange={() => toggleColumn(c.key)}
              />
              <span>{c.menuLabel}</span>
            </label>
          ))}
        </PopupMenu>
      )}

      {rowMenu && (() => {
        // The menu acts on the whole multi-selection when the right-clicked row is part of it;
        // otherwise just that row (openRowMenu already collapsed the selection to it). `count` drives
        // the labels and which item set renders.
        const ids =
          selectedIds.size > 0 && selectedIds.has(rowMenu.match.id)
            ? [...selectedIds]
            : [rowMenu.match.id];
        const count = ids.length;
        const targets = ids
          .map((id) => matchById.get(id))
          .filter((m): m is MatchSummary => m != null);
        const revealable = targets.filter((m) => canReveal(m));
        const bulkStar = (starred: boolean): void => {
          targets.forEach((m) => void toggleStar(m.id, starred));
          closeRowMenu();
        };
        return (
          <PopupMenu
            x={rowMenu.x}
            y={rowMenu.y}
            onClose={closeRowMenu}
            ariaLabel={count > 1 ? `Actions for ${count} recordings` : 'Recording actions'}
          >
            {count === 1 ? (
              <>
                <button
                  type="button"
                  className="ctx-item"
                  role="menuitem"
                  onClick={() => {
                    selectMatch(rowMenu.match.id);
                    closeRowMenu();
                  }}
                >
                  Open in player
                </button>
                <button
                  type="button"
                  className="ctx-item"
                  role="menuitem"
                  onClick={() => {
                    void toggleStar(rowMenu.match.id, !rowMenu.match.starred);
                    closeRowMenu();
                  }}
                >
                  {rowMenu.match.starred ? 'Unstar' : 'Star (keep from auto-delete)'}
                </button>
                {canReveal(rowMenu.match) && (
                  <button
                    type="button"
                    className="ctx-item"
                    role="menuitem"
                    onClick={() => {
                      // Best-effort: a failed reveal (file vanished between render and click) is a
                      // no-op, never an unhandled rejection.
                      void window.dotarec?.revealPath(rowMenu.match.videoPath as string).catch(() => {});
                      closeRowMenu();
                    }}
                  >
                    Reveal in folder
                  </button>
                )}
                <button
                  type="button"
                  className="ctx-item"
                  role="menuitem"
                  onClick={() => {
                    copyMatchId(rowMenu.match);
                    closeRowMenu();
                  }}
                >
                  Copy match ID
                </button>
                <div className="ctx-sep" role="separator" />
                <button
                  type="button"
                  className="ctx-item ctx-item-danger"
                  role="menuitem"
                  onClick={() => {
                    if (menuDeleteArmed) {
                      // deleteMatch rethrows on a failed API call; swallow it here (a stale row is
                      // reconciled by the next load) so it can't surface as an unhandled rejection.
                      void deleteMatch(rowMenu.match.id).catch(() => {});
                      closeRowMenu();
                    } else {
                      setMenuDeleteArmed(true);
                    }
                  }}
                >
                  {menuDeleteArmed ? 'Click to confirm delete' : 'Delete recording'}
                </button>
              </>
            ) : (
              <>
                <div className="ctx-menu-head" aria-hidden="true">
                  {count} SELECTED
                </div>
                <button
                  type="button"
                  className="ctx-item"
                  role="menuitem"
                  onClick={() => bulkStar(true)}
                >
                  Star {count} (keep from auto-delete)
                </button>
                <button
                  type="button"
                  className="ctx-item"
                  role="menuitem"
                  onClick={() => bulkStar(false)}
                >
                  Unstar {count}
                </button>
                {revealable.length > 0 && (
                  <button
                    type="button"
                    className="ctx-item"
                    role="menuitem"
                    onClick={() => {
                      revealable.forEach(
                        (m) => void window.dotarec?.revealPath(m.videoPath as string).catch(() => {}),
                      );
                      closeRowMenu();
                    }}
                  >
                    Reveal {revealable.length} in folder
                  </button>
                )}
                <button
                  type="button"
                  className="ctx-item"
                  role="menuitem"
                  onClick={() => {
                    const text = targets.map((m) => String(m.dotaMatchId ?? m.id)).join('\n');
                    void navigator.clipboard?.writeText(text).catch(() => {});
                    closeRowMenu();
                  }}
                >
                  Copy {count} match IDs
                </button>
                <div className="ctx-sep" role="separator" />
                <button
                  type="button"
                  className="ctx-item ctx-item-danger"
                  role="menuitem"
                  onClick={() => {
                    if (menuDeleteArmed) {
                      // deleteMatches skips any failed delete; the next load() reconciles a straggler.
                      void deleteMatches(ids).catch(() => {});
                      setSelectedIds(new Set<number>());
                      closeRowMenu();
                    } else {
                      setMenuDeleteArmed(true);
                    }
                  }}
                >
                  {menuDeleteArmed ? `Click to confirm delete (${count})` : `Delete ${count} recordings`}
                </button>
              </>
            )}
          </PopupMenu>
        );
      })()}
    </div>
  );
}
