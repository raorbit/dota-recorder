import { useLibraryStore, type Bucket } from '../store/library';
import type { BucketCounts, Status } from '../api/client';
import './sidebar.css';

export type SettingsTab = 'recording' | 'gsi' | 'general';

interface SidebarProps {
  // Which top-level view is showing, so the SETTINGS group can mark its active
  // item and the RECORDINGS group can clear its highlight when in settings.
  readonly view: 'library' | 'settings';
  readonly settingsTab: SettingsTab;
  readonly onOpenSettings: (tab: SettingsTab) => void;
  readonly onOpenLibrary: () => void;
}

interface BucketDef {
  readonly key: Bucket;
  readonly label: string;
  // 'accent' badges sit on the red pill (the primary bucket); 'gold' on the gold
  // pill (Clips); 'plain' on the muted dark pill. Mirrors the mockup.
  readonly badge: 'accent' | 'gold' | 'plain';
}

// The two prominent buckets (Ranked active style + Unranked) and the four compact
// rows below them, in the mockup's order.
const PRIMARY_BUCKETS: readonly BucketDef[] = [
  { key: 'ranked', label: 'Ranked', badge: 'accent' },
  { key: 'unranked', label: 'Unranked', badge: 'plain' },
];

const SECONDARY_BUCKETS: readonly BucketDef[] = [
  { key: 'turbo', label: 'Turbo', badge: 'plain' },
  { key: 'abilityDraft', label: 'Ability Draft', badge: 'plain' },
];

// Manual recordings and saved Clips have no creation UI in v0.1, so these buckets
// would always read 0 and advertise features that don't exist. Shown only if the
// backend ever reports a count (future-proof, mirroring the Unsorted bucket).
const OPTIONAL_BUCKETS: readonly BucketDef[] = [
  { key: 'manual', label: 'Manual', badge: 'plain' },
  { key: 'clips', label: 'Clips', badge: 'gold' },
];

function countFor(counts: BucketCounts, key: Bucket): number {
  return counts[key];
}

// Derives the status card's label + state from the live Status. Recording is the
// loudest state (red pulse); else GSI connectivity drives green/idle; null means
// the core/socket is not yet reporting.
function deriveStatus(status: Status | null): {
  readonly title: string;
  readonly text: string;
  readonly state: 'unknown' | 'idle' | 'watching' | 'recording';
} {
  if (status === null) {
    return { title: 'RECORDER', text: 'connecting…', state: 'unknown' };
  }
  if (status.recording) {
    return { title: 'RECORDER', text: 'Recording · live', state: 'recording' };
  }
  if (status.gsiConnected) {
    return { title: 'RECORDER', text: 'Watching · GSI ok', state: 'watching' };
  }
  return { title: 'RECORDER', text: 'Idle · no GSI', state: 'idle' };
}

export function Sidebar({
  view,
  settingsTab,
  onOpenSettings,
  onOpenLibrary,
}: SidebarProps): React.JSX.Element {
  const counts = useLibraryStore((s) => s.counts);
  const activeBucket = useLibraryStore((s) => s.bucket);
  const status = useLibraryStore((s) => s.status);
  const setBucket = useLibraryStore((s) => s.setBucket);

  const card = deriveStatus(status);

  const selectBucket = (key: Bucket): void => {
    setBucket(key);
    onOpenLibrary();
  };

  const renderBucket = (def: BucketDef): React.JSX.Element => {
    const active = view === 'library' && activeBucket === def.key;
    const count = countFor(counts, def.key);
    return (
      <button
        key={def.key}
        type="button"
        className="sb-bucket"
        data-active={active ? 'true' : 'false'}
        onClick={() => selectBucket(def.key)}
      >
        <span className="sb-diamond" data-active={active ? 'true' : 'false'} aria-hidden="true" />
        <span className="sb-bucket-label">{def.label}</span>
        <span className="sb-bucket-spacer" />
        <span className="sb-badge" data-kind={def.badge}>
          {count}
        </span>
      </button>
    );
  };

  // Pending / un-enriched recordings live under "Unsorted" — only shown when one
  // exists, so the sidebar matches the mockup against empty data but still gives
  // un-enriched rows a home (they are NEVER defaulted into Ranked).
  const showUnsorted = counts.unsorted > 0;

  const settingsItems: readonly { readonly tab: SettingsTab; readonly label: string }[] = [
    { tab: 'recording', label: 'Recording' },
    { tab: 'gsi', label: 'Game State Integration' },
    { tab: 'general', label: 'General' },
  ];

  return (
    <aside className="sidebar">
      <div className="sb-status" data-state={card.state}>
        <div className="sb-status-row">
          <span className="sb-status-title">{card.title}</span>
          {card.state === 'recording' && (
            <span className="sb-recpulse" aria-hidden="true" />
          )}
        </div>
        <div className="sb-status-text" data-state={card.state}>
          {card.text}
        </div>
      </div>

      <div className="sb-group-label">RECORDINGS</div>

      <div className="sb-primary">
        {PRIMARY_BUCKETS.map(renderBucket)}
        {showUnsorted &&
          renderBucket({ key: 'unsorted', label: 'Unsorted', badge: 'plain' })}
      </div>

      <div className="sb-secondary">
        {SECONDARY_BUCKETS.map(renderBucket)}
        {OPTIONAL_BUCKETS.filter((def) => countFor(counts, def.key) > 0).map(renderBucket)}
      </div>

      <div className="sb-group-label sb-group-label-settings">SETTINGS</div>
      <div className="sb-settings">
        {settingsItems.map((item) => (
          <button
            key={item.tab}
            type="button"
            className="sb-nav"
            data-active={view === 'settings' && settingsTab === item.tab ? 'true' : 'false'}
            onClick={() => onOpenSettings(item.tab)}
          >
            {item.label}
          </button>
        ))}
      </div>

      <div className="sb-version">v0.1.0</div>
    </aside>
  );
}
