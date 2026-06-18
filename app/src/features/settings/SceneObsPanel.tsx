import { useEffect, useState } from 'react';
import {
  fetchSettings,
  updateSettings,
  type Settings,
  type SettingsPatch,
} from '../../api/client';
import type { StatusSnapshot } from '../../api/client';
import './scene-obs-panel.css';

type LoadState = 'loading' | 'ready' | 'error';
type SaveState = 'idle' | 'saving' | 'saved' | 'error';

interface SceneObsPanelProps {
  // Live OBS status, lifted from App's StatusSocket. Null until the first frame
  // (or while the core is unreachable) so we can render an "unknown" state rather
  // than a misleading "disconnected".
  readonly obs: StatusSnapshot['obs'] | null;
}

// Pixel of guidance the user MUST follow in OBS for capture to work at all. A
// green GSI card with OBS not set up records nothing, so this block is loud and
// always visible.
function RequiredSetup(): React.JSX.Element {
  return (
    <div className="obs-doc" role="note">
      <div className="obs-doc-title">Required OBS setup</div>
      <ol className="obs-doc-steps">
        <li>
          In OBS, open <strong>Tools → WebSocket Server Settings</strong> and{' '}
          <strong>Enable WebSocket server</strong> (this app speaks v5; OBS 28+).
        </li>
        <li>
          <strong>Set a server password</strong> in that dialog, then enter the same
          host, port, and password here. A blank password will not connect.
        </li>
        <li>
          Create a <strong>scene</strong> with a <strong>Display Capture</strong> or{' '}
          <strong>Game Capture</strong> source so the match is actually on screen.
        </li>
        <li>
          Add an <strong>unmuted desktop-audio input</strong> to that scene so game
          audio is recorded.
        </li>
      </ol>
      <p className="obs-doc-foot">
        Leave OBS running while recording. The recorder connects on save and when a
        match is scheduled; if OBS is closed it shows as disconnected and retries.
      </p>
    </div>
  );
}

function obsStatusLabel(obs: StatusSnapshot['obs'] | null): {
  readonly text: string;
  readonly state: 'unknown' | 'disconnected' | 'connected' | 'recording';
} {
  if (obs === null) return { text: 'unknown', state: 'unknown' };
  if (!obs.connected) return { text: 'disconnected', state: 'disconnected' };
  if (obs.recording) return { text: 'recording', state: 'recording' };
  if (obs.sceneActive) return { text: 'connected · scene active', state: 'connected' };
  return { text: 'connected · no scene', state: 'connected' };
}

export function SceneObsPanel({ obs }: SceneObsPanelProps): React.JSX.Element {
  const [loadState, setLoadState] = useState<LoadState>('loading');
  const [settings, setSettings] = useState<Settings | null>(null);

  // Form fields. Host/port mirror the stored settings; password is write-only and
  // starts empty on every load (we never prefill or echo a secret).
  const [host, setHost] = useState('');
  const [port, setPort] = useState('');
  const [password, setPassword] = useState('');

  const [saveState, setSaveState] = useState<SaveState>('idle');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async (): Promise<void> => {
      try {
        const s = await fetchSettings();
        if (cancelled) return;
        setSettings(s);
        setHost(s.obsHost);
        setPort(String(s.obsPort));
        setPassword('');
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

    const portNum = Number(port);
    if (!Number.isInteger(portNum) || portNum < 1 || portNum > 65_535) {
      setError('Port must be an integer between 1 and 65535.');
      setSaveState('error');
      return;
    }

    // Build a minimal patch: only send the password when the user typed one, so a
    // blank field leaves the stored secret untouched.
    const patch: SettingsPatch = password
      ? { obsHost: host.trim(), obsPort: portNum, obsPassword: password }
      : { obsHost: host.trim(), obsPort: portNum };

    try {
      const updated = await updateSettings(patch);
      setSettings(updated);
      setHost(updated.obsHost);
      setPort(String(updated.obsPort));
      setPassword(''); // never retain the typed secret in component state
      setSaveState('saved');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save settings.');
      setSaveState('error');
    }
  };

  const status = obsStatusLabel(obs);
  const passwordStored = settings?.obsPasswordSet ?? false;

  return (
    <section className="obs-panel" aria-label="Scene and OBS settings">
      <header className="obs-panel-head">
        <h2 className="obs-panel-title">Scene &amp; OBS</h2>
        <div className="obs-conn" data-state={status.state}>
          <span className="obs-conn-dot" data-state={status.state} aria-hidden="true" />
          <span className="obs-conn-text">{status.text}</span>
        </div>
      </header>

      {loadState === 'loading' && <p className="obs-muted">Loading settings…</p>}

      {loadState === 'error' && (
        <p className="obs-error" role="alert">
          Could not load settings from the core. Is it running?
        </p>
      )}

      {loadState === 'ready' && (
        <form
          className="obs-form"
          onSubmit={(e) => {
            e.preventDefault();
            void onSave();
          }}
        >
          <div className="obs-field">
            <label className="obs-label" htmlFor="obs-host">
              OBS host
            </label>
            <input
              id="obs-host"
              className="obs-input"
              type="text"
              value={host}
              autoComplete="off"
              spellCheck={false}
              placeholder="127.0.0.1"
              onChange={(e) => setHost(e.target.value)}
            />
          </div>

          <div className="obs-field">
            <label className="obs-label" htmlFor="obs-port">
              OBS port
            </label>
            <input
              id="obs-port"
              className="obs-input"
              type="number"
              inputMode="numeric"
              min={1}
              max={65535}
              value={port}
              autoComplete="off"
              placeholder="4455"
              onChange={(e) => setPort(e.target.value)}
            />
          </div>

          <div className="obs-field">
            <label className="obs-label" htmlFor="obs-password">
              OBS password
              <span
                className="obs-secret-state"
                data-set={passwordStored ? 'true' : 'false'}
              >
                {passwordStored ? 'password set' : 'not set'}
              </span>
            </label>
            <input
              id="obs-password"
              className="obs-input"
              type="password"
              value={password}
              autoComplete="new-password"
              placeholder={passwordStored ? 'leave blank to keep current' : 'enter password'}
              onChange={(e) => setPassword(e.target.value)}
            />
            <p className="obs-hint">
              Write-only. The stored password is never shown. Leave blank to keep the
              current one.
            </p>
          </div>

          {error !== null && (
            <p className="obs-error" role="alert">
              {error}
            </p>
          )}

          <div className="obs-actions">
            <button
              className="obs-save"
              type="submit"
              disabled={saveState === 'saving'}
            >
              {saveState === 'saving' ? 'Saving…' : 'Save'}
            </button>
            {saveState === 'saved' && <span className="obs-saved">Saved</span>}
          </div>
        </form>
      )}

      <RequiredSetup />
    </section>
  );
}
