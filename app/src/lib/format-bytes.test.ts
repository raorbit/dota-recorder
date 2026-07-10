import { describe, expect, it } from 'vitest';
import { formatBytes } from './format-bytes';

describe('formatBytes', () => {
  it('formats raw bytes with no decimal', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(1)).toBe('1 B');
  });

  it('steps up through the units at 1024 boundaries', () => {
    expect(formatBytes(1024)).toBe('1 KB');
    expect(formatBytes(1024 ** 2)).toBe('1 MB');
    expect(formatBytes(1024 ** 3)).toBe('1 GB');
    expect(formatBytes(1024 ** 4)).toBe('1 TB');
    expect(formatBytes(1024 ** 5)).toBe('1 PB');
  });

  it('keeps one decimal at GB+ (the free-space readout case)', () => {
    expect(formatBytes(12.4 * 1024 ** 3)).toBe('12.4 GB');
    expect(formatBytes(4.25 * 1024 ** 3)).toBe('4.3 GB'); // rounds to one decimal
  });

  it('drops a trailing .0 for a whole value', () => {
    expect(formatBytes(8 * 1024 ** 3)).toBe('8 GB');
  });

  it('caps at PB rather than inventing a larger unit', () => {
    expect(formatBytes(2048 * 1024 ** 5)).toBe('2048 PB');
  });

  it('returns 0 B for zero, negative, or non-finite input', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(-100)).toBe('0 B');
    expect(formatBytes(NaN)).toBe('0 B');
    expect(formatBytes(Infinity)).toBe('0 B');
  });
});
