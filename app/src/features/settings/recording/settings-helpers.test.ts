import { describe, expect, it } from 'vitest';
import type { DriveUsage } from '../../../api/client';
import {
  CAP_MIN_GB,
  PADDING_MAX_S,
  PADDING_MIN_S,
  capExceedsDrive,
  clampCapGb,
  clampPadding,
  fmtSize,
  withStoredOption,
} from './settings-helpers';

describe('clampCapGb', () => {
  it('snaps a blank/NaN field up to the floor', () => {
    expect(clampCapGb(Number(''))).toBe(CAP_MIN_GB);
    expect(clampCapGb(NaN)).toBe(CAP_MIN_GB);
  });

  it('snaps a below-floor value up to the floor', () => {
    expect(clampCapGb(0)).toBe(CAP_MIN_GB);
    expect(clampCapGb(CAP_MIN_GB - 1)).toBe(CAP_MIN_GB);
  });

  it('rounds a fractional value in range', () => {
    expect(clampCapGb(500.4)).toBe(500);
    expect(clampCapGb(500.6)).toBe(501);
  });

  it('passes an in-range integer through', () => {
    expect(clampCapGb(CAP_MIN_GB)).toBe(CAP_MIN_GB);
    expect(clampCapGb(1234)).toBe(1234);
  });
});

describe('clampPadding', () => {
  it('snaps a blank/NaN/below-min field up to the floor', () => {
    expect(clampPadding(Number(''))).toBe(PADDING_MIN_S);
    expect(clampPadding(NaN)).toBe(PADDING_MIN_S);
    expect(clampPadding(0)).toBe(PADDING_MIN_S);
  });

  it('clamps a value past the ceiling down to the max', () => {
    expect(clampPadding(PADDING_MAX_S + 1)).toBe(PADDING_MAX_S);
    expect(clampPadding(1000)).toBe(PADDING_MAX_S);
  });

  it('rounds a fractional in-range value', () => {
    expect(clampPadding(8.4)).toBe(8);
    expect(clampPadding(8.6)).toBe(9);
  });
});

describe('fmtSize', () => {
  it('returns an em dash for null/undefined', () => {
    expect(fmtSize(null)).toBe('—');
    expect(fmtSize(undefined)).toBe('—');
  });

  it('formats byte counts as rounded GB below 1024 GB', () => {
    expect(fmtSize(50 * 1024 ** 3)).toBe('50 GB');
    expect(fmtSize(0)).toBe('0 GB');
  });

  it('switches to TB at or past 1024 GB', () => {
    expect(fmtSize(1024 * 1024 ** 3)).toBe('1.0 TB');
    expect(fmtSize(2 * 1024 ** 4)).toBe('2.0 TB');
  });
});

describe('withStoredOption', () => {
  const presets = [
    { value: '1920x1080', label: '1920 × 1080 (1080p)' },
    { value: '3840x2160', label: '3840 × 2160 (4K)' },
  ];

  it('returns the presets unchanged when the stored value is one of them', () => {
    expect(withStoredOption(presets, '1920x1080')).toBe(presets);
  });

  it('prepends a stored value outside the list, labelled with its own value', () => {
    expect(withStoredOption(presets, '2560x1080')).toEqual([
      { value: '2560x1080', label: '2560x1080' },
      ...presets,
    ]);
  });

  it('labels a blank stored value with an em dash', () => {
    expect(withStoredOption(presets, '')).toEqual([{ value: '', label: '—' }, ...presets]);
  });
});

describe('capExceedsDrive', () => {
  const usage = (usedBytes: number, freeBytes: number | null): DriveUsage => ({
    role: 'archive',
    path: 'D:\\archive',
    capBytes: 0,
    usedBytes,
    freeBytes,
    totalBytes: freeBytes === null ? null : usedBytes + freeBytes,
  });

  it('is false when usage is missing or free space is unknown', () => {
    expect(capExceedsDrive(500, undefined)).toBe(false);
    expect(capExceedsDrive(500, usage(0, null))).toBe(false);
  });

  it('is false when the cap fits within used + free bytes', () => {
    // 100 GB reachable, 100 GB cap fits exactly.
    expect(capExceedsDrive(100, usage(0, 100 * 1024 ** 3))).toBe(false);
  });

  it('is true when the cap exceeds used + free bytes', () => {
    // 100 GB reachable, 101 GB cap does not fit.
    expect(capExceedsDrive(101, usage(0, 100 * 1024 ** 3))).toBe(true);
  });
});
