// The launch-at-login entry and the flag it carries. Kept free of Electron imports so the rules can
// be unit-tested: app-prefs.ts applies them to the registry and main.ts reads the flag.

// Arg the auto-start (login) launch carries so the app knows to start hidden in the
// tray rather than popping a window on every sign-in.
export const HIDDEN_LAUNCH_ARG = '--hidden';

/** Whether a launch asked to stay in the tray: the login entry's flag is among its arguments. */
export function isHiddenLaunch(argv: readonly string[]): boolean {
  return argv.includes(HIDDEN_LAUNCH_ARG);
}
