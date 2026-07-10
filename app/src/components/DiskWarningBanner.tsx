import { useLibraryStore } from '../store/library';
import { formatBytes } from '../lib/format-bytes';
import './disk-warning-banner.css';

// A calm, dismissible low-disk warning bar shown across the top of the main column on every view.
// Subscribes to the store's `diskWarning` (pushed by the core's low-disk `error` frame; see
// startLibrary) and hides itself when there is none. Dismissal clears the store slice — but the banner
// is intentionally sticky: the core emits no "disk healthy" event, so it re-publishes on the next
// low-disk check and the banner returns after dismissal.
//
// The core's raw `message` is a log line that embeds bare byte integers ("… 5368709120 bytes free …"),
// so rather than render it verbatim in a hi-fi UI we compose a concise line from the structured fields
// (freeBytes formatted human-readably) and keep the full message on the container's `title` tooltip.
// Warning tone, not error-red — matches the settings "note" warnings (--gold, circular `!` badge).
export function DiskWarningBanner(): React.JSX.Element | null {
  const warning = useLibraryStore((s) => s.diskWarning);
  const setDiskWarning = useLibraryStore((s) => s.setDiskWarning);

  if (warning === null) return null;

  return (
    <div className="dwb-banner" role="status" title={warning.message}>
      <span className="dwb-icon" aria-hidden="true">
        !
      </span>
      <p className="dwb-text">
        <strong className="dwb-label">Low disk space</strong>
        {' — '}
        <span className="dwb-free">{formatBytes(warning.freeBytes)} free</span>
        {'. Recording continues; the oldest unstarred recordings are pruned to make room.'}
      </p>
      <button
        type="button"
        className="dwb-dismiss"
        aria-label="Dismiss low disk space warning"
        onClick={() => setDiskWarning(null)}
      >
        &#x2715;
      </button>
    </div>
  );
}
