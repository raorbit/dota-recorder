// App-level (OS/window-behavior) preferences owned by the Electron main process and
// persisted next to the renderer's other state in userData. These are deliberately
// kept OUT of the core's settings.json: launch-at-login and tray behavior are desktop
// concerns the core knows nothing about, and the main process must read them before
// (and independently of) the bridge being up.
import { app } from 'electron';
import * as fs from 'node:fs';
import * as path from 'node:path';

export interface AppPrefs {
  // Start the app automatically when the user signs in to Windows (hidden in the tray).
  readonly launchAtLogin: boolean;
}

const DEFAULTS: AppPrefs = { launchAtLogin: false };

// Arg the auto-start (login) launch carries so the app knows to start hidden in the
// tray rather than popping a window on every sign-in.
export const HIDDEN_LAUNCH_ARG = '--hidden';

function prefsPath(): string {
  return path.join(app.getPath('userData'), 'app-prefs.json');
}

export function readPrefs(): AppPrefs {
  try {
    const parsed = JSON.parse(fs.readFileSync(prefsPath(), 'utf8')) as Partial<AppPrefs>;
    return { launchAtLogin: parsed.launchAtLogin === true };
  } catch {
    // Missing or unreadable prefs are normal on first run: fall back to defaults.
    return { ...DEFAULTS };
  }
}

function writePrefs(prefs: AppPrefs): void {
  try {
    fs.writeFileSync(prefsPath(), `${JSON.stringify(prefs, null, 2)}\n`, 'utf8');
  } catch {
    /* best-effort; a failed write just means the toggle won't persist across launches. */
  }
}

export function getLaunchAtLogin(): boolean {
  return readPrefs().launchAtLogin;
}

export function setLaunchAtLogin(value: boolean): boolean {
  writePrefs({ ...readPrefs(), launchAtLogin: value });
  applyLaunchAtLogin();
  return getLaunchAtLogin();
}

/**
 * Reconcile the OS login item with the stored pref. Only writes the real registry Run
 * entry in PACKAGED builds: in dev the executable is the dev electron binary inside
 * node_modules, so registering a login item would point Windows at a broken dev launch.
 * We still persist the intent in dev (so the toggle reflects/round-trips), and the
 * installed app applies it for real on its next launch.
 */
export function applyLaunchAtLogin(): void {
  if (!app.isPackaged) return;
  const openAtLogin = readPrefs().launchAtLogin;
  app.setLoginItemSettings({
    openAtLogin,
    // Auto-start carries --hidden so the app boots straight to the tray; a manual
    // launch (no arg) opens the window as usual.
    args: openAtLogin ? [HIDDEN_LAUNCH_ARG] : [],
  });
}
