import type { StorageLocation, StorageUsage } from '../../../api/client';
import { CAP_MIN_GB, capExceedsDrive, clampCapGb, fmtSize } from './settings-helpers';

interface ArchiveDrivesSectionProps {
  readonly storageLocations: StorageLocation[];
  // Per-drive usage, matched to each row by path; null until stat'd.
  readonly usage: StorageUsage | null;
  // Per-row validation messages keyed by the row's stable id (set when a blocked save flags a row).
  readonly driveErrors: Record<string, string>;
  readonly addDrive: () => void;
  readonly removeDrive: (i: number) => void;
  readonly setDriveCap: (i: number, capGb: number) => void;
  readonly setDrivePath: (i: number, path: string) => void;
  readonly onBrowseDrive: (i: number) => Promise<void>;
}

export function ArchiveDrivesSection({
  storageLocations,
  usage,
  driveErrors,
  addDrive,
  removeDrive,
  setDriveCap,
  setDrivePath,
  onBrowseDrive,
}: ArchiveDrivesSectionProps): React.JSX.Element {
  return (
    <section className="rec-card" aria-label="Archive drives">
      <h3 className="rec-sec">Archive drives</h3>
      <p className="rec-desc aud-intro">
        Finished recordings are moved off the recording drive onto these drives, filling each up to
        its cap. The newest matches stay on the fast recording drive. Leave empty to keep everything
        on the recording drive.
      </p>

      {storageLocations.map((loc, i) => {
        const u = usage?.drives.find((x) => x.role === 'archive' && x.path === loc.path);
        const warn = capExceedsDrive(loc.capGb, u);
        const rowError = driveErrors[loc.id];
        // Distinct accessible names so a screen-reader user can tell the (otherwise
        // identical) per-drive controls apart: prefer the entered path, fall back to
        // the 1-based row number for a still-blank drive.
        const driveName = loc.path.trim() !== '' ? loc.path.trim() : `drive ${i + 1}`;
        return (
          <div className="rec-row drv-row" key={loc.id}>
            <div className="rec-rowlabel drv-path">
              <div className="rec-control rec-path">
                <input
                  className="rec-input rec-pathinput"
                  type="text"
                  value={loc.path}
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="D:\dota-archive"
                  aria-label={`Folder for archive ${driveName}`}
                  onChange={(e) => setDrivePath(i, e.target.value)}
                />
                <button
                  type="button"
                  className="rec-browse"
                  aria-label={`Browse for archive ${driveName} folder`}
                  onClick={() => void onBrowseDrive(i)}
                >
                  Browse
                </button>
              </div>
              {u && (
                <p className="rec-desc drv-free">
                  {fmtSize(u.usedBytes)} used · {fmtSize(u.freeBytes)} free of{' '}
                  {fmtSize(u.totalBytes)}
                </p>
              )}
              {warn && (
                // role="alert" + visible "Warning:" prefix — same rationale as the
                // Max-storage warning above (assertive, not colour-only).
                <p className="rec-note rec-note-warn" role="alert">
                  <span className="rec-note-icon" aria-hidden="true">
                    !
                  </span>
                  <strong className="rec-note-label">Warning:</strong> Cap {loc.capGb} GB exceeds
                  this drive — it will fill before the cap is reached.
                </p>
              )}
              {rowError && (
                // Save was blocked because this row is blank/invalid (FIX ii); keep the
                // row visible and tell the user what to fix instead of silently dropping it.
                <p className="rec-note rec-note-warn" role="alert">
                  <span className="rec-note-icon" aria-hidden="true">
                    !
                  </span>
                  <strong className="rec-note-label">Warning:</strong> {rowError}
                </p>
              )}
            </div>
            <div className="rec-control drv-control">
              <div className="rec-capfield">
                <input
                  className="rec-input rec-capinput"
                  type="number"
                  min={CAP_MIN_GB}
                  step={10}
                  aria-label={`Cap in GB for archive ${driveName}`}
                  value={loc.capGb}
                  onChange={(e) => {
                    // Keep the raw value while typing; clamp on blur/save so a
                    // momentarily-cleared field can't persist (or send) 0.
                    const v = Number(e.target.value);
                    setDriveCap(i, Number.isFinite(v) ? v : 0);
                  }}
                  onBlur={() => setDriveCap(i, clampCapGb(loc.capGb))}
                />
                <span className="rec-capunit">GB</span>
              </div>
              <button
                type="button"
                className="aud-remove"
                aria-label={`Remove archive ${driveName}`}
                title="Remove drive"
                onClick={() => removeDrive(i)}
              >
                ✕
              </button>
            </div>
          </div>
        );
      })}

      <div className="rec-row aud-addrow">
        <div className="rec-rowlabel">
          <span className="rec-label">Add a drive</span>
          <p className="rec-desc">Use a larger drive to archive older recordings.</p>
        </div>
        <div className="rec-control aud-add">
          <button type="button" className="rec-browse aud-add-kind" onClick={addDrive}>
            + Add drive
          </button>
        </div>
      </div>
    </section>
  );
}
