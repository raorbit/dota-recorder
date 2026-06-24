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

  // Editable form fields. resolution and videoDir are user-set; encoder is
  // auto-probed and written back by the core, so it is shown read-only here.
  const [resolution, setResolution] = useState('');
  const [videoDir, setVideoDir] = useState('');
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

  const onSave = async (): Promise<void> => {
    setSaveState('saving');
    setError(null);

    const trimmedAccount = accountId.trim();
    const patch: SettingsPatch = {
      resolution: resolution.trim(),
      videoDir: videoDir.trim(),
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
      setAccountId(updated.accountId !== null ? String(updated.accountId) : '');
      setSaveState('saved');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save settings.');
      setSaveState('error');
    }
  };

  const status = recorderStatusLabel(obs);

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
          <div className="rec-field">
            <label className="rec-label" htmlFor="rec-resolution">
              Resolution
            </label>
            <input
              id="rec-resolution"
              className="rec-input"
              type="text"
              value={resolution}
              autoComplete="off"
              spellCheck={false}
              placeholder="1920x1080"
              onChange={(e) => setResolution(e.target.value)}
            />
          </div>

          <div className="rec-field">
            <label className="rec-label" htmlFor="rec-encoder">
              Encoder
              <span className="rec-derived">auto-detected</span>
            </label>
            <input
              id="rec-encoder"
              className="rec-input"
              type="text"
              value={settings?.encoder ?? ''}
              readOnly
              tabIndex={-1}
            />
            <p className="rec-hint">
              The best available hardware encoder is probed automatically. This shows
              the one currently in use.
            </p>
          </div>

          <div className="rec-field">
            <label className="rec-label" htmlFor="rec-videodir">
              Output folder
            </label>
            <input
              id="rec-videodir"
              className="rec-input"
              type="text"
              value={videoDir}
              autoComplete="off"
              spellCheck={false}
              placeholder="C:\Users\you\Videos\dota-recorder"
              onChange={(e) => setVideoDir(e.target.value)}
            />
          </div>

          <div className="rec-field">
            <label className="rec-label" htmlFor="rec-account">
              Account ID
              <span className="rec-derived">captured from GSI</span>
            </label>
            <input
              id="rec-account"
              className="rec-input"
              type="text"
              inputMode="numeric"
              value={accountId}
              autoComplete="off"
              spellCheck={false}
              placeholder="96828122"
              onChange={(e) => setAccountId(e.target.value.replace(/\D/g, ''))}
            />
          </div>

          {error !== null && (
            <p className="rec-error" role="alert">
              {error}
            </p>
          )}

          <div className="rec-actions">
            <button
              className="rec-save"
              type="submit"
              disabled={saveState === 'saving'}
            >
              {saveState === 'saving' ? 'Saving…' : 'Save'}
            </button>
            {saveState === 'saved' && <span className="rec-saved">Saved</span>}
          </div>
        </form>
      )}
    </section>
  );
}
