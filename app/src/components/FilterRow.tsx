import { useLibraryStore, type ResultFilter } from '../store/library';
import './filter-row.css';

const SEGMENTS: readonly { readonly key: ResultFilter; readonly label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'wins', label: 'Wins' },
  { key: 'losses', label: 'Losses' },
];

// The filter row above the match table: a segmented win/loss toggle and a search
// field, both wired to the store. (A date filter can land here later — the store
// already carries dateFilter — but a dead disabled control is omitted until then.)
export function FilterRow(): React.JSX.Element {
  const resultFilter = useLibraryStore((s) => s.resultFilter);
  const search = useLibraryStore((s) => s.search);
  const setResultFilter = useLibraryStore((s) => s.setResultFilter);
  const setSearch = useLibraryStore((s) => s.setSearch);

  return (
    <div className="fr-row">
      <div className="fr-segment" role="tablist" aria-label="Result filter">
        {SEGMENTS.map((seg) => (
          <button
            key={seg.key}
            type="button"
            role="tab"
            aria-selected={resultFilter === seg.key}
            className="fr-seg-btn"
            data-active={resultFilter === seg.key ? 'true' : 'false'}
            onClick={() => setResultFilter(seg.key)}
          >
            {seg.label}
          </button>
        ))}
      </div>

      <label className="fr-search">
        <span className="fr-search-icon" aria-hidden="true">
          🔍
        </span>
        <input
          className="fr-search-input"
          type="search"
          value={search}
          placeholder="Search hero, mode, match id…"
          autoComplete="off"
          spellCheck={false}
          onChange={(e) => setSearch(e.target.value)}
          aria-label="Search matches"
        />
      </label>
    </div>
  );
}
