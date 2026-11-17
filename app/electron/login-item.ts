// The launch-at-login entry and the flag it carries. Kept free of Electron imports so the rules can
// be unit-tested: app-prefs.ts applies them to the registry and main.ts reads the flag.

// Arg the auto-start (login) launch carries so the app knows to start hidden in the
// tray rather than popping a window on every sign-in.
export const HIDDEN_LAUNCH_ARG = '--hidden';

/** Whether a launch asked to stay in the tray: the login entry's flag is among its arguments. */
export function isHiddenLaunch(argv: readonly string[]): boolean {
  return argv.includes(HIDDEN_LAUNCH_ARG);
}

// The Run-key value the login entry is written under. Left to itself Electron uses whatever the
// AppUserModelID is at the moment of the call, so the name is spelled out here and does not follow
// the appId: it is the name installs since 0.1.20 already hold, and a renamed entry is a second
// entry, because nothing removes the one under the old name.
export const LOGIN_ITEM_NAME = 'dev.dotarec.recorder';

// That is how this one came about. Up to 0.1.19 the app set no AppUserModelID, so Electron filed the
// entry under its default, "electron.app." and the product name. 0.1.20 set the appId, the entry
// moved to the new name, and an install that had launch-at-login on kept both: two launches at every
// sign-in, the second of which opened the window.
export const LEGACY_LOGIN_ITEM_NAME = 'electron.app.Dota 2 Recorder';

/** One call to Electron's setLoginItemSettings. */
export interface LoginItemWrite {
  readonly name: string;
  readonly openAtLogin: boolean;
  readonly args: string[];
}

/**
 * The registry writes that make the login entry match the preference. The entry under the old name
 * always goes; the current one is written with --hidden, so the app boots straight to the tray
 * (a manual launch carries no argument and opens the window as usual), or removed.
 */
export function loginItemWrites(openAtLogin: boolean): LoginItemWrite[] {
  return [
    { name: LEGACY_LOGIN_ITEM_NAME, openAtLogin: false, args: [] },
    { name: LOGIN_ITEM_NAME, openAtLogin, args: openAtLogin ? [HIDDEN_LAUNCH_ARG] : [] },
  ];
}
