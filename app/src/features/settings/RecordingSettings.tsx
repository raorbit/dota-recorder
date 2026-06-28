import { useEffect, useState } from 'react';
import {
  fetchSettings,
  updateSettings,
  type Settings,
  type SettingsPatch,
} from '../../api/client';
import type { StatusSnapshot } from '../../api/client';
import './recording-settings.css';

type LoadState = 'loading' | 'ready' | 'error';
type SaveState = 'idle' | 'saving' | 'saved' | 'error';

interface RecordingSettingsProps {
  // Live recorder status, lifted from App's StatusSocket. Null until the first
  // frame (or while the core is unreachable) so we can render an "unknown" state
  // rather than a misleading error.
  readonly obs: StatusSnapshot['obs'] | null;
}

// Resolution presets offered in the dropdown. A stored value outside this list
// (e.g. an ultrawide) is preserved and shown as an extra leading option.
const RES_PRESETS: ReadonlyArray<{ readonly value: string; readonly label: string }> = [
  { value: '1280x720', label: '1280 × 720 (720p)' },
  { value: '1920x1080', label: '1920 × 1080 (1080p)' },
  { value: '2560x1440', label: '2560 × 1440 (1440p)' },
  { value: '3840x2160', label: '3840 × 2160 (4K)' },
];

// The core auto-probes a hardware encoder and writes back a short OBS token; map
// it to a human label. Unknown tokens fall through to the raw value.
const ENCODER_LABELS: Record<string, string> = {
  nvenc: 'NVIDIA NVENC (H.264)',
  amd: 'AMD AMF (H.264)',
  qsv: 'Intel QuickSync (H.264)',
  x264: 'x264 (software)',
};

const RETENTION_MIN = 10;
const RETENTION_MAX = 500;

// Single self-describing "Recorder" indicator. The recorder is app-managed now,
// so we deliberately avoid any OBS jargon; the prefix makes the chip readable on
// its own. Evaluated top-down, first match wins — connected is gated before the
// recording check so a dropped backend reads as an error, not a stale "recording".
function recorderStatusLabel(obs: StatusSnapshot['obs'] | null): {
  readonly text: string;
  readonly state: 'unknown' | 'error' | 'recording' | 'ready' | 'preparing';
} {
  if (obs === null) return { text: 'Recorder: connecting…', state: 'unknown' };
  if (!obs.connected) return { text: 'Recorder: error', state: 'error' };
  if (obs.recording) return { text: 'Recorder: recording', state: 'recording' };
  if (obs.sceneActive) return { text: 'Recorder: ready', state: 'ready' };
  return { text: 'Recorder: preparing', state: 'preparing' };
}

export function RecordingSettings({ obs }: RecordingSettingsProps): React.JSX.Element {
  const [loadState, setLoadState] = useState<LoadState>('loading');
  const [settings, setSettings] = useState<Settings | null>(null);

  // Editable form fields. resolution/videoDir/retention are user-set; encoder is
  // auto-probed and written back by the core, so it is shown read-only here.
  const [resolution, setResolution] = useState('');
  const [videoDir, setVideoDir] = useState('');
  const [retentionGb, setRetentionGb] = useState(50);
  const [accountId, setAccountId] = useState('');

  const [saveState, setSaveState] = useState<SaveState>('idle');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async (): Promise<void> => {
      try {
        const s = await fetchSettings();
        if (cancelled) return;
        setSettings(s);
        setResolution(s.resolution);
        setVideoDir(s.videoDir);
        setRetentionGb(s.retentionCapGb);
        setAccountId(s.accountId !== null ? String(s.accountId) : '');
        setLoadState('ready');
      } catch {
        if (cancelled) return;
        setLoadState('error');
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // A changed field marks the form dirty so Save is only offered when it matters.
  const dirty =
    settings !== null &&
    (resolution !== settings.resolution ||
      videoDir.trim() !== settings.videoDir ||
      retentionGb !== settings.retentionCapGb ||
      accountId.trim() !== (settings.accountId !== null ? String(settings.accountId) : ''));

  const onBrowse = async (): Promise<void> => {
    const picked = await window.dotarec?.selectFolder();
    if (picked) {
      setVideoDir(picked);
      setSaveState('idle');
    }
  };

  const onSave = async (): Promise<void> => {
    setSaveState('saving');
    setError(null);

    const trimmedAccount = accountId.trim();
    const patch: SettingsPatch = {
      resolution: resolution.trim(),
      videoDir: videoDir.trim(),
      retentionCapGb: retentionGb,
      // null means "leave unchanged" on the wire, so a blanked field clears via an explicit flag.
      ...(trimmedAccount === ''
        ? { clearAccountId: true }
        : { accountId: Number(trimmedAccount) }),
    };

    try {
      const updated = await updateSettings(patch);
      setSettings(updated);
      setResolution(updated.resolution);
      setVideoDir(updated.videoDir);
      setRetentionGb(updated.retentionCapGb);
      setAccountId(updated.accountId !== null ? String(updated.accountId) : '');
      setSaveState('saved');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save settings.');
      setSaveState('error');
    }
  };

  const status = recorderStatusLabel(obs);
  const encoderToken = settings?.encoder ?? '';
  const encoderLabel = ENCODER_LABELS[encoderToken] ?? (encoderToken || 'Detecting…');
  const resOptions = RES_PRESETS.some((p) => p.value === resolution)
    ? RES_PRESETS
    : [{ value: resolution, label: resolution || '—' }, ...RES_PRESETS];

  return (
    <section className="rec-panel" aria-label="Recording settings">
      <header className="rec-panel-head">
        <h2 className="rec-panel-title">Recording</h2>
        <div className="rec-conn" data-state={status.state}>
          <span className="rec-conn-dot" data-state={status.state} aria-hidden="true" />
          <span className="rec-conn-text">{status.text}</span>
        </div>
      </header>

      {loadState === 'loading' && <p className="rec-muted">Loading settings…</p>}

      {loadState === 'error' && (
        <p className="rec-error" role="alert">
          Could not load settings from the core. Is it running?
        </p>
      )}

      {loadState === 'ready' && (
        <form
          className="rec-form"
          onSubmit={(e) => {
            e.preventDefault();
            void onSave();
          }}
        >
          <section className="rec-card">
            <h3 className="rec-sec">Output</h3>
            <div className="rec-row">
              <div className="rec-rowlabel">
                <label className="rec-label" htmlFor="rec-resolution">
                  Resolution
                </label>
                <p className="rec-desc">Canvas and output size of the recording.</p>
              </div>
              <div className="rec-control">
                <select
                  id="rec-resolution"
                  className="rec-select"
                  value={resolution}
                  onChange={(e) => {
                    setResolution(e.target.value);
                    setSaveState('idle');
                  }}
                >
                  {resOptions.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </div>
            </div>
          </section>

          <section className="rec-card">
            <h3 className="rec-sec">Video</h3>
            <div className="rec-row">
              <div className="rec-rowlabel">
                <label className="rec-label">
                  Encoder
                  <span className="rec-badge">auto</span>
                </label>
                <p className="rec-desc">Best hardware encoder, detected for your GPU.</p>
              </div>
              <div className="rec-control">
                <div className="rec-readonly" title={encoderToken}>
                  {encoderLabel}
                </div>
              </div>
            </div>
          </section>

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
                  onClick={() => void onBrowse()}
                >
                  Browse
                </button>
              </div>
            </div>
            <div className="rec-row">
              <div className="rec-rowlabel">
                <label className="rec-label" htmlFor="rec-retention">
                  Max storage
                </label>
                <p className="rec-desc">Oldest unstarred recordings are removed first.</p>
              </div>
              <div className="rec-control rec-slider">
                <input
                  id="rec-retention"
                  className="rec-range"
                  type="range"
                  min={RETENTION_MIN}
                  max={RETENTION_MAX}
                  step={10}
                  value={retentionGb}
                  onChange={(e) => {
                    setRetentionGb(Number(e.target.value));
                    setSaveState('idle');
                  }}
                />
                <span className="rec-rangeval">{retentionGb} GB</span>
              </div>
            </div>
          </section>

          <section className="rec-card">
            <h3 className="rec-sec">Account</h3>
            <div className="rec-row">
              <div className="rec-rowlabel">
                <label className="rec-label" htmlFor="rec-account">
                  Account ID
                  <span className="rec-badge rec-badge-muted">from GSI</span>
                </label>
                <p className="rec-desc">Tags your own deaths and kills. Auto-captured.</p>
              </div>
              <div className="rec-control">
                <input
                  id="rec-account"
                  className="rec-input"
                  type="text"
                  inputMode="numeric"
                  value={accountId}
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="96828122"
                  onChange={(e) => {
                    setAccountId(e.target.value.replace(/\D/g, ''));
                    setSaveState('idle');
                  }}
                />
              </div>
            </div>
          </section>

          {error !== null && (
            <p className="rec-error" role="alert">
              {error}
            </p>
          )}

          <div className="rec-actions">
            <button
              className="rec-save"
              type="submit"
              disabled={saveState === 'saving' || !dirty}
            >
              {saveState === 'saving' ? 'Saving…' : 'Save changes'}
            </button>
            {saveState === 'saved' && !dirty && <span className="rec-saved">Saved</span>}
          </div>
        </form>
      )}
    </section>
  );
}
