import { describe, expect, it } from 'vitest';
import { HIDDEN_LAUNCH_ARG, isHiddenLaunch } from './login-item';

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
