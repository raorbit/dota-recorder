import { describe, expect, it } from 'vitest';
import { revealablePath } from './reveal-path-guard';

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
