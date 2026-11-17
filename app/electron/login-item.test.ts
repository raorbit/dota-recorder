import { describe, expect, it } from 'vitest';
import {
  HIDDEN_LAUNCH_ARG,
  LEGACY_LOGIN_ITEM_NAME,
  LOGIN_ITEM_NAME,
  isHiddenLaunch,
  loginItemWrites,
} from './login-item';

describe('loginItemWrites', () => {
  it('keeps the names installs already hold in the Run key', () => {
    // A changed name here is a second entry on every machine that has launch-at-login on.
    expect(LOGIN_ITEM_NAME).toBe('dev.dotarec.recorder');
    expect(LEGACY_LOGIN_ITEM_NAME).toBe('electron.app.Dota 2 Recorder');
  });

  it('writes the current entry with the tray flag when launch-at-login is on', () => {
    expect(loginItemWrites(true)).toContainEqual({
      name: LOGIN_ITEM_NAME,
      openAtLogin: true,
      args: [HIDDEN_LAUNCH_ARG],
    });
  });

  it('removes the current entry when launch-at-login is off', () => {
    expect(loginItemWrites(false)).toContainEqual({
      name: LOGIN_ITEM_NAME,
      openAtLogin: false,
      args: [],
    });
  });

  it('removes the entry under the old name either way, and never writes it', () => {
    for (const openAtLogin of [true, false]) {
      const legacy = loginItemWrites(openAtLogin).filter((w) => w.name === LEGACY_LOGIN_ITEM_NAME);
      expect(legacy).toEqual([{ name: LEGACY_LOGIN_ITEM_NAME, openAtLogin: false, args: [] }]);
    }
  });

  it('touches one entry per name', () => {
    const names = loginItemWrites(true).map((w) => w.name);
    expect(new Set(names).size).toBe(names.length);
  });
});

describe('isHiddenLaunch', () => {
  const exe = 'C:\\Users\\me\\AppData\\Local\\Programs\\Dota 2 Recorder\\Dota 2 Recorder.exe';

  it('is true for the login entry, wherever Chromium puts its own switches', () => {
    expect(isHiddenLaunch([exe, HIDDEN_LAUNCH_ARG])).toBe(true);
    expect(isHiddenLaunch([exe, '--allow-file-access-from-files', HIDDEN_LAUNCH_ARG])).toBe(true);
  });

  it('is false for a launch from the Start menu or the taskbar', () => {
    expect(isHiddenLaunch([exe])).toBe(false);
    expect(isHiddenLaunch([exe, '--updated'])).toBe(false);
  });

  it('does not take a longer argument that only starts with the flag', () => {
    expect(isHiddenLaunch([exe, '--hidden-something'])).toBe(false);
  });
});
