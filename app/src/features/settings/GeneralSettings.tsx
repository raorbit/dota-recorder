import { useEffect, useState } from 'react';
import './general-settings.css';
import { useLibraryStore } from '../../store/library';
import type { UpdateState } from '../../../electron/bridge-contract';

// General (app behavior) settings. These are OS/window-level prefs the Electron main
// process owns over IPC (window.dotarec), NOT the core's /settings — launch-at-login,
// auto-update, and tray behavior are desktop concerns the core knows nothing about. Outside
// Electron (plain browser dev) window.dotarec is undefined, so the controls degrade to disabled.

// Map an update snapshot to the status card's title + description. `autoOn` disambiguates the
// 'available' state, which persists without a download in two cases: auto-update is off (manual
// "Check now" found one) or the download is deferred because a match is recording.
function updateSummary(u: UpdateState | null, autoOn: boolean): { title: string; desc: string } {
  switch (u?.status) {
    case 'checking':
      return { title: 'Checking for updates…', desc: 'Looking for a newer version.' };
    case 'available':
      return {
        title: `Update available — v${u.version ?? ''}`,
        desc: u.recording
          ? "The download will start once you're not recording."
          : autoOn
            ? 'Starting download…'
            : 'Turn on Automatic updates to download and install it.',
      };
    case 'downloading':
      return { title: `Downloading update… ${u.percent ?? 0}%`, desc: 'You can keep using the app.' };
    case 'downloaded':
      return { title: `Update ready — v${u.version ?? ''}`, desc: 'Restart to finish installing.' };
    case 'error':
      return { title: 'Update check failed', desc: u.error ?? 'Something went wrong.' };
    case 'not-available':
      return { title: 'Up to date', desc: "You're on the latest version." };
    case 'idle':
    default:
      // Before the first check completes (and all session, if auto-update is off) — don't claim
      // "up to date" without having actually checked.
      return { title: 'Not checked yet', desc: 'No update check has completed yet.' };
  }
}

export function GeneralSettings(): React.JSX.Element {
  // null = not yet loaded (the toggle is disabled until we know the real OS state).
  const [launchAtLogin, setLaunchAtLogin] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  const [autoUpdate, setAutoUpdate] = useState<boolean | null>(null);
  const [autoUpdateBusy, setAutoUpdateBusy] = useState(false);
  // Live update lifecycle, pushed by the main process into the library store.
  const update = useLibraryStore((s) => s.update);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const v = (await window.dotarec?.getLaunchAtLogin?.()) ?? false;
      if (!cancelled) setLaunchAtLogin(v);
    })();
    void (async () => {
      const v = (await window.dotarec?.getAutoUpdate?.()) ?? true;
      if (!cancelled) setAutoUpdate(v);
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const toggleLaunch = async (next: boolean): Promise<void> => {
    if (!window.dotarec?.setLaunchAtLogin) return;
    setBusy(true);
    setLaunchAtLogin(next); // optimistic
    try {
      // The main process returns the value it actually applied; trust it over the guess.
      const applied = await window.dotarec.setLaunchAtLogin(next);
      setLaunchAtLogin(applied);
    } finally {
      setBusy(false);
    }
  };

  const toggleAutoUpdate = async (next: boolean): Promise<void> => {
    if (!window.dotarec?.setAutoUpdate) return;
    setAutoUpdateBusy(true);
    setAutoUpdate(next); // optimistic
    try {
      const applied = await window.dotarec.setAutoUpdate(next);
      setAutoUpdate(applied);
    } finally {
      setAutoUpdateBusy(false);
    }
  };

  const available = typeof window.dotarec?.setLaunchAtLogin === 'function';
  const updatesAvailable = typeof window.dotarec?.setAutoUpdate === 'function';
  const on = launchAtLogin === true;
  const autoOn = autoUpdate === true;

  const summary = updateSummary(update, autoOn);
  const status = update?.status ?? 'idle';
  const checking = status === 'checking' || status === 'downloading';
  const downloaded = status === 'downloaded';
  const deferredByRecording = downloaded && update?.recording === true;
  const deferredByUnreachable = downloaded && update?.unreachable === true;

  return (
    <section className="gen-panel" aria-label="General settings">
      <header className="gen-panel-head">
        <h2 className="gen-panel-title">General</h2>
      </header>

      <div className="gen-row">
        <div className="gen-row-text">
          <div className="gen-row-title">Launch at login</div>
          <p className="gen-row-desc">
            Start Dota 2 Recorder automatically when you sign in to Windows, hidden in the system
            tray so it's ready to record without opening a window.
          </p>
        </div>
        <button
          type="button"
          className="gen-switch"
          role="switch"
          aria-checked={on}
          aria-label="Launch at login"
          data-on={on ? 'true' : 'false'}
          disabled={!available || busy || launchAtLogin === null}
          onClick={() => void toggleLaunch(!on)}
        >
          <span className="gen-switch-knob" aria-hidden="true" />
        </button>
      </div>

      <div className="gen-row">
        <div className="gen-row-text">
          <div className="gen-row-title">Automatic updates</div>
          <p className="gen-row-desc">
            Download new versions in the background and install them with one click. Updates never
            interrupt a match — they wait until you're not recording.
          </p>
        </div>
        <button
          type="button"
          className="gen-switch"
          role="switch"
          aria-checked={autoOn}
          aria-label="Automatic updates"
          data-on={autoOn ? 'true' : 'false'}
          disabled={!updatesAvailable || autoUpdateBusy || autoUpdate === null}
          onClick={() => void toggleAutoUpdate(!autoOn)}
        >
          <span className="gen-switch-knob" aria-hidden="true" />
        </button>
      </div>

      <div className="gen-note" role="note">
        <div className="gen-update-head">
          <div className="gen-note-title" data-ready={downloaded ? 'true' : 'false'}>
            {summary.title}
          </div>
          {updatesAvailable && (
            <button
              type="button"
              className="gen-linkbtn"
              disabled={checking}
              onClick={() => void window.dotarec?.checkForUpdates?.()}
            >
              Check now
            </button>
          )}
        </div>
        <p className="gen-row-desc">{summary.desc}</p>
        {deferredByRecording && (
          <p className="gen-row-desc gen-update-warn">
            Can't restart while a match is recording — try again once it ends.
          </p>
        )}
        {deferredByUnreachable && (
          <p className="gen-row-desc gen-update-warn">
            Can't restart right now — the recorder isn't responding. Try again in a moment.
          </p>
        )}
        {downloaded && (
          <div className="gen-update-actions">
            <button
              type="button"
              className="gen-action-btn"
              onClick={() => void window.dotarec?.installUpdateNow?.()}
            >
              Restart to update
            </button>
            {update?.version && (
              <button
                type="button"
                className="gen-linkbtn"
                onClick={() => void window.dotarec?.openReleaseNotes?.(update.version)}
              >
                Release notes
              </button>
            )}
          </div>
        )}
      </div>

      <div className="gen-note" role="note">
        <div className="gen-note-title">Closing the window</div>
        <p className="gen-row-desc">
          Closing the window keeps Dota 2 Recorder running in the system tray so it can keep
          auto-recording your matches. Click the tray icon to reopen it, or right-click it and
          choose <strong>Quit Dota 2 Recorder</strong> to exit completely.
        </p>
      </div>
    </section>
  );
}
