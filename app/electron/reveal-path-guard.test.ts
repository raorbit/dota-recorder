import * as fs from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { pathIsAccessible, revealablePath } from './reveal-path-guard';

describe('revealablePath', () => {
  it('accepts an absolute Windows drive path', () => {
    expect(revealablePath('C:\\Users\\me\\Videos\\dota-recorder\\m.mp4')).toBe(
      'C:\\Users\\me\\Videos\\dota-recorder\\m.mp4',
    );
  });

  it('accepts a forward-slash absolute Windows path', () => {
    expect(revealablePath('C:/Users/me/m.mp4')).toBe('C:/Users/me/m.mp4');
  });

  it('accepts a UNC path', () => {
    expect(revealablePath('\\\\nas\\share\\clips\\m.mp4')).toBe('\\\\nas\\share\\clips\\m.mp4');
  });

  it('trims surrounding whitespace', () => {
    expect(revealablePath('   C:\\x\\y.mp4   ')).toBe('C:\\x\\y.mp4');
  });

  it('rejects non-string input', () => {
    expect(revealablePath(123)).toBeNull();
    expect(revealablePath(null)).toBeNull();
    expect(revealablePath(undefined)).toBeNull();
    expect(revealablePath({ path: 'C:\\x' })).toBeNull();
  });

  it('rejects blank input', () => {
    expect(revealablePath('')).toBeNull();
    expect(revealablePath('   ')).toBeNull();
  });

  it('rejects relative paths', () => {
    expect(revealablePath('foo\\bar.mp4')).toBeNull();
    expect(revealablePath('.\\x.mp4')).toBeNull();
    expect(revealablePath('clips/m.mp4')).toBeNull();
  });

  it('rejects a path containing a .. traversal segment', () => {
    expect(revealablePath('C:\\a\\..\\b.mp4')).toBeNull();
    expect(revealablePath('C:/a/../b.mp4')).toBeNull();
    expect(revealablePath('C:\\..\\Windows\\system32')).toBeNull();
  });

  it('does not treat .. inside a name component as traversal', () => {
    expect(revealablePath('C:\\a\\b..c\\d.mp4')).toBe('C:\\a\\b..c\\d.mp4');
  });
});

describe('pathIsAccessible', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('resolves true when the file is accessible', async () => {
    vi.spyOn(fs.promises, 'access').mockResolvedValue(undefined);
    await expect(pathIsAccessible('C:\\x\\y.mp4')).resolves.toBe(true);
  });

  it('resolves false (not-accessible) when the file is missing', async () => {
    vi.spyOn(fs.promises, 'access').mockRejectedValue(
      Object.assign(new Error('ENOENT'), { code: 'ENOENT' }),
    );
    await expect(pathIsAccessible('C:\\gone.mp4')).resolves.toBe(false);
  });

  // The core guarantee: an unreachable UNC share whose fs.access never settles must NOT hang the caller.
  // The timeout wins the race and resolves false, so the (main-thread) reveal handler stays responsive
  // instead of blocking for the full SMB timeout.
  it('does not hang on an unreachable path: times out to false without the probe settling', async () => {
    vi.useFakeTimers();
    // Simulate an offline UNC target: fs.access never resolves/rejects.
    vi.spyOn(fs.promises, 'access').mockReturnValue(new Promise<void>(() => {}));

    const result = pathIsAccessible('\\\\offline-nas\\share\\m.mp4', 1_000);
    // Advance past the timeout; the probe is still pending, so the timeout branch must win.
    await vi.advanceTimersByTimeAsync(1_000);

    await expect(result).resolves.toBe(false);
  });
});
