import type { Dispatch, SetStateAction } from 'react';
import type { DriveUsage, StorageUsage } from '../../../api/client';
import { CAP_MIN_GB, capExceedsDrive, clampCapGb, fmtSize } from './settings-helpers';
import type { SaveState } from './settings-helpers';

interface StorageSectionProps {
  readonly videoDir: string;
  readonly setVideoDir: Dispatch<SetStateAction<string>>;
  readonly retentionGb: number;
  readonly setRetentionGb: Dispatch<SetStateAction<number>>;
  // Library-wide totals (drives the "storing … across all drives" note). Null until stat'd.
  readonly usage: StorageUsage | null;
  // The recording (active) drive's usage row, backing the free/total readout + cap warning.
  readonly activeUsage: DriveUsage | undefined;
  // True once the output folder is actually changed, surfacing the "existing recordings stay" note.
  readonly folderChanged: boolean;
  readonly onBrowse: () => Promise<void>;
  readonly setSaveState: Dispatch<SetStateAction<SaveState>>;
}

export function StorageSection({
  videoDir,
  setVideoDir,
  retentionGb,
  setRetentionGb,
  usage,
  activeUsage,
  folderChanged,
  onBrowse,
  setSaveState,
}: StorageSectionProps): React.JSX.Element {
  return (
    <section className="rec-card">
      <h3 className="rec-sec">Storage</h3>
      <div className="rec-row">
        <div className="rec-rowlabel">
          <label className="rec-label" htmlFor="rec-videodir">
            Output folder
          </label>
          <p className="rec-desc">Where recordings and thumbnails are written.</p>
        </div>
        <div className="rec-control rec-path">
          <input
            id="rec-videodir"
            className="rec-input rec-pathinput"
            type="text"
            value={videoDir}
            autoComplete="off"
            spellCheck={false}
            placeholder="C:\Users\you\Videos\dota-recorder"
            onChange={(e) => {
              setVideoDir(e.target.value);
              setSaveState('idle');
            }}
          />
          <button
            type="button"
            className="rec-browse"
            aria-label="Browse for the output folder"
            onClick={() => void onBrowse()}
          >
            Browse
          </button>
        </div>
      </div>
      {folderChanged && (
        <p className="rec-note" role="status">
          <span className="rec-note-icon" aria-hidden="true">
            i
          </span>
          Existing recordings stay in their current folder — only new recordings will be saved here.
        </p>
      )}
      <div className="rec-row">
        <div className="rec-rowlabel">
          <label className="rec-label" htmlFor="rec-retention">
            Max storage
          </label>
          <p className="rec-desc">
            Disk budget for the recording drive. Oldest unstarred recordings are removed first
            (across all drives).
          </p>
        </div>
        <div className="rec-control rec-capfield">
          <input
            id="rec-retention"
            className="rec-input rec-capinput"
            type="number"
            min={CAP_MIN_GB}
            step={10}
            value={retentionGb}
            onChange={(e) => {
              // Keep the raw value while typing (so the field can be cleared and
              // retyped); NaN is held as 0 and snapped to the floor on blur/save.
              const v = Number(e.target.value);
              setRetentionGb(Number.isFinite(v) ? v : 0);
              setSaveState('idle');
            }}
            // Reflect a sensible value once the user leaves the field: a cleared/<=0
            // cap snaps up to the floor rather than persisting (and later sending) 0.
            onBlur={() => setRetentionGb((v) => clampCapGb(v))}
          />
          <span className="rec-capunit">GB</span>
          {activeUsage && (
            <span className="rec-capfree">
              {fmtSize(activeUsage.freeBytes)} free of {fmtSize(activeUsage.totalBytes)}
            </span>
          )}
        </div>
      </div>
      {capExceedsDrive(retentionGb, activeUsage) && (
        // role="alert" (assertive): a cap that can't be reached is an actionable
        // problem the user should hear immediately, not a passive status. The visible
        // "Warning:" prefix carries the meaning without relying on the gold colour
        // (the icon is aria-hidden, so it's the prefix that reaches a screen reader).
        <p className="rec-note rec-note-warn" role="alert">
          <span className="rec-note-icon" aria-hidden="true">
            !
          </span>
          <strong className="rec-note-label">Warning:</strong> Cap {retentionGb} GB exceeds this
          drive — it will fill before the cap is reached.
        </p>
      )}
      {usage && (
        <p className="rec-note" role="status">
          <span className="rec-note-icon" aria-hidden="true">
            i
          </span>
          Storing {fmtSize(usage.totalBytes)} of recordings across all drives —{' '}
          {fmtSize(usage.starredBytes)} starred (never auto-deleted).
        </p>
      )}
    </section>
  );
}
