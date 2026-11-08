import { useLibraryStore } from '../store/library';
import { formatBytes } from '../lib/format-bytes';
import './disk-warning-banner.css';

// A calm, dismissible low-disk warning bar shown across the top of the main column on every view.
// Subscribes to the store's `diskWarning` (pushed by the core's low-disk `error` frame, and seeded
// from GET /status when the window mounts or reconnects; see startLibrary) and hides itself when
// there is none. Dismissal clears the store slice for this window's lifetime: the next hourly check
// that still finds the drive low re-publishes, and a window opened later reads it from /status, so a
// real shortage always comes back. The OS notification for it is the main process's, not this one's.
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
