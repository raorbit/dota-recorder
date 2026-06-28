import { useEffect, useState } from 'react';
import './general-settings.css';

// General (app behavior) settings. These are OS/window-level prefs the Electron main
// process owns over IPC (window.dotarec), NOT the core's /settings — launch-at-login
// and tray behavior are desktop concerns the core knows nothing about. Outside Electron
// (plain browser dev) window.dotarec is undefined, so the toggle degrades to disabled.
export function GeneralSettings(): React.JSX.Element {
  // null = not yet loaded (the toggle is disabled until we know the real OS state).
  const [launchAtLogin, setLaunchAtLogin] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const v = (await window.dotarec?.getLaunchAtLogin?.()) ?? false;
      if (!cancelled) setLaunchAtLogin(v);
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

  const available = typeof window.dotarec?.setLaunchAtLogin === 'function';
  const on = launchAtLogin === true;

  return (
    <section className="gen-panel" aria-label="General settings">
      <header className="gen-panel-head">
        <h2 className="gen-panel-title">General</h2>
      </header>

      <div className="gen-row">
        <div className="gen-row-text">
          <div className="gen-row-title">Launch at login</div>
          <p className="gen-row-desc">
            Start Dota 2 Recorder automatically when you sign in to Windows, hidden in the
            system tray so it's ready to record without opening a window.
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
