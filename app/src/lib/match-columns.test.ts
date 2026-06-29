import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MatchSummary } from '../api/client';
import {
  COLS_PREF_KEY,
  COLUMN_META_BY_KEY,
  SORT_PREF_KEY,
  compareMatches,
  defaultVisibleKeysFor,
  formatDuration,
  formatPlayedAt,
  loadSortByBucket,
  loadVisibleByBucket,
  pathDirname,
  sanitizeKeys,
  storageInfoOf,
  type ColumnMeta,
} from './match-columns';

// A MatchSummary with every field defaulted; tests override only what they exercise.
function mk(over: Partial<MatchSummary> & { id: number }): MatchSummary {
  return {
    dotaMatchId: null,
    recordKind: 'match',
    enrichmentState: 'enriched',
    hero: null,
    kills: null,
    deaths: null,
    assists: null,
    gpm: null,
    xpm: null,
    netWorth: null,
    lastHits: null,
    result: null,
    lobbyType: null,
    gameMode: null,
    rankTier: null,
    mmrDelta: null,
    durationS: null,
    playedAt: null,
    videoPath: null,
    thumbPath: null,
    fileSizeBytes: null,
    starred: false,
    createdAt: 0,
    recordStartedWallMs: null,
    ...over,
  };
}

const gpmCol = COLUMN_META_BY_KEY.get('gpm') as ColumnMeta;
const resultCol = COLUMN_META_BY_KEY.get('result') as ColumnMeta;
const storageCol = COLUMN_META_BY_KEY.get('storage') as ColumnMeta;

describe('pathDirname', () => {
  it('returns the folder of a nested Windows path, separators preserved', () => {
    expect(pathDirname('C:\\Users\\me\\Videos\\dota-recorder\\m.mp4')).toBe(
      'C:\\Users\\me\\Videos\\dota-recorder',
    );
  });
  it('handles forward slashes', () => {
    expect(pathDirname('D:/dota-archive/m.mp4')).toBe('D:/dota-archive');
  });
  it('returns the immediate parent folder for a deeper path', () => {
    expect(pathDirname('D:\\dota-archive\\2026\\m.mp4')).toBe('D:\\dota-archive\\2026');
  });
  it('strips a trailing separator first (defensive; real inputs are file paths)', () => {
    expect(pathDirname('D:\\dota-archive\\')).toBe('D:');
  });
  it('returns the whole string when there is no separator', () => {
    expect(pathDirname('m.mp4')).toBe('m.mp4');
  });
});

describe('storageInfoOf', () => {
  it('marks a pruned (null videoPath) recording as removed', () => {
    const info = storageInfoOf(mk({ id: 1, videoPath: null }));
    expect(info.removed).toBe(true);
    expect(info.label).toBe('—');
  });
  it('marks a blank videoPath as removed', () => {
    expect(storageInfoOf(mk({ id: 1, videoPath: '   ' })).removed).toBe(true);
  });
  it('returns the folder as the label and the full path as the title', () => {
    const info = storageInfoOf(mk({ id: 1, videoPath: 'D:\\dota-archive\\m.mp4' }));
    expect(info).toEqual({ label: 'D:\\dota-archive', removed: false, title: 'D:\\dota-archive\\m.mp4' });
  });
});

describe('formatDuration', () => {
  it('returns em-dash for null/negative/non-finite', () => {
    expect(formatDuration(null)).toBe('—');
    expect(formatDuration(-5)).toBe('—');
    expect(formatDuration(Number.NaN)).toBe('—');
  });
  it('formats sub-hour as m:ss', () => {
    expect(formatDuration(0)).toBe('0:00');
    expect(formatDuration(65)).toBe('1:05');
    expect(formatDuration(600)).toBe('10:00');
  });
  it('formats hour+ as h:mm:ss', () => {
    expect(formatDuration(3661)).toBe('1:01:01');
  });
  it('rounds to the nearest second', () => {
    expect(formatDuration(89.6)).toBe('1:30');
  });
});

describe('formatPlayedAt', () => {
  it('returns em-dash for null / unparseable', () => {
    expect(formatPlayedAt(null)).toBe('—');
    expect(formatPlayedAt(Number.NaN)).toBe('—');
  });
  it('formats a real timestamp as "HH:MM · DD Mon"', () => {
    // Asserted by shape to stay timezone-independent.
    expect(formatPlayedAt(Date.UTC(2026, 5, 29, 12, 34))).toMatch(/^\d{2}:\d{2} · \d{2} \w{3}$/);
  });
});

describe('compareMatches', () => {
  it('orders by numeric value ascending / descending', () => {
    const a = mk({ id: 1, gpm: 100 });
    const b = mk({ id: 2, gpm: 200 });
    expect(compareMatches(a, b, gpmCol, 'asc')).toBeLessThan(0);
    expect(compareMatches(a, b, gpmCol, 'desc')).toBeGreaterThan(0);
  });

  it('sorts null/empty values last in BOTH directions', () => {
    const has = mk({ id: 1, gpm: 100 });
    const none = mk({ id: 2, gpm: null });
    expect(compareMatches(has, none, gpmCol, 'asc')).toBeLessThan(0);
    expect(compareMatches(has, none, gpmCol, 'desc')).toBeLessThan(0);
    expect(compareMatches(none, has, gpmCol, 'asc')).toBeGreaterThan(0);
    expect(compareMatches(none, has, gpmCol, 'desc')).toBeGreaterThan(0);
  });

  it('breaks ties by newest id first, regardless of direction', () => {
    const older = mk({ id: 1, gpm: 50 });
    const newer = mk({ id: 2, gpm: 50 });
    // equal values -> b.id - a.id, so the higher id sorts first
    expect(compareMatches(older, newer, gpmCol, 'asc')).toBeGreaterThan(0);
    expect(compareMatches(newer, older, gpmCol, 'asc')).toBeLessThan(0);
    // both empty also tie-breaks by id
    const e1 = mk({ id: 1, gpm: null });
    const e2 = mk({ id: 2, gpm: null });
    expect(compareMatches(e1, e2, gpmCol, 'asc')).toBeGreaterThan(0);
  });

  it('orders string columns with locale compare', () => {
    const win = mk({ id: 1, result: 'win' });
    const loss = mk({ id: 2, result: 'loss' });
    expect(compareMatches(win, loss, resultCol, 'asc')).toBeGreaterThan(0); // 'loss' < 'win'
    expect(compareMatches(win, loss, resultCol, 'desc')).toBeLessThan(0);
  });

  it('sorts the storage column by folder, removed rows last', () => {
    const active = mk({ id: 1, videoPath: 'C:\\active\\m.mp4' });
    const archive = mk({ id: 2, videoPath: 'D:\\archive\\m.mp4' });
    const removed = mk({ id: 3, videoPath: null });
    expect(compareMatches(active, archive, storageCol, 'asc')).toBeLessThan(0); // c < d
    expect(compareMatches(active, removed, storageCol, 'asc')).toBeLessThan(0); // removed last
    expect(compareMatches(active, removed, storageCol, 'desc')).toBeLessThan(0);
  });

  it('actually sorts an array (integration over the real comparator)', () => {
    const rows = [mk({ id: 1, gpm: 300 }), mk({ id: 2, gpm: null }), mk({ id: 3, gpm: 100 })];
    const ascIds = [...rows].sort((a, b) => compareMatches(a, b, gpmCol, 'asc')).map((m) => m.id);
    expect(ascIds).toEqual([3, 1, 2]); // 100, 300, then null last
    const descIds = [...rows].sort((a, b) => compareMatches(a, b, gpmCol, 'desc')).map((m) => m.id);
    expect(descIds).toEqual([1, 3, 2]); // 300, 100, then null last
  });
});

describe('defaultVisibleKeysFor', () => {
  it('includes MMR only for the Ranked tab', () => {
    expect(defaultVisibleKeysFor('ranked')).toContain('mmr');
    expect(defaultVisibleKeysFor('turbo')).not.toContain('mmr');
    expect(defaultVisibleKeysFor('unranked')).not.toContain('mmr');
  });
  it('uses the base set for non-ranked tabs and base+mmr for ranked', () => {
    expect(defaultVisibleKeysFor('turbo')).toEqual(['result', 'kda', 'gpm', 'mode', 'date']);
    expect(defaultVisibleKeysFor('ranked')).toEqual(['result', 'kda', 'gpm', 'mode', 'date', 'mmr']);
  });
});

describe('sanitizeKeys', () => {
  it('keeps known non-fixed keys and drops unknown + fixed (hero) keys', () => {
    expect(sanitizeKeys(['gpm', 'bogus', 'hero', 'date'])).toEqual(['gpm', 'date']);
  });
  it('returns [] for an array with no valid keys', () => {
    expect(sanitizeKeys(['bogus', 'hero'])).toEqual([]);
  });
  it('returns null for a non-array', () => {
    expect(sanitizeKeys('nope')).toBeNull();
    expect(sanitizeKeys(null)).toBeNull();
    expect(sanitizeKeys({ a: 1 })).toBeNull();
  });
});

// In-memory localStorage so the per-bucket preference loaders can be exercised in Node.
class MemStorage {
  private store = new Map<string, string>();
  getItem(k: string): string | null {
    return this.store.has(k) ? (this.store.get(k) as string) : null;
  }
  setItem(k: string, v: string): void {
    this.store.set(k, String(v));
  }
  removeItem(k: string): void {
    this.store.delete(k);
  }
  clear(): void {
    this.store.clear();
  }
  key(i: number): string | null {
    return [...this.store.keys()][i] ?? null;
  }
  get length(): number {
    return this.store.size;
  }
}

describe('loadVisibleByBucket', () => {
  beforeEach(() => vi.stubGlobal('localStorage', new MemStorage()));
  afterEach(() => vi.unstubAllGlobals());

  it('returns {} when nothing is stored', () => {
    expect(loadVisibleByBucket()).toEqual({});
  });
  it('loads and sanitizes per-bucket column sets', () => {
    localStorage.setItem(
      COLS_PREF_KEY,
      JSON.stringify({ ranked: ['gpm', 'bogus', 'hero'], turbo: ['date'] }),
    );
    expect(loadVisibleByBucket()).toEqual({ ranked: ['gpm'], turbo: ['date'] });
  });
  it('returns {} on corrupt JSON', () => {
    localStorage.setItem(COLS_PREF_KEY, '{ not json');
    expect(loadVisibleByBucket()).toEqual({});
  });
  it('returns {} when the stored value is an array, not a map', () => {
    localStorage.setItem(COLS_PREF_KEY, JSON.stringify(['gpm', 'date']));
    expect(loadVisibleByBucket()).toEqual({});
  });
});

describe('loadSortByBucket', () => {
  beforeEach(() => vi.stubGlobal('localStorage', new MemStorage()));
  afterEach(() => vi.unstubAllGlobals());

  it('returns {} when nothing is stored', () => {
    expect(loadSortByBucket()).toEqual({});
  });
  it('keeps valid sort states and drops entries with a bad key or direction', () => {
    localStorage.setItem(
      SORT_PREF_KEY,
      JSON.stringify({
        ranked: { key: 'gpm', dir: 'asc' },
        badKey: { key: 'bogus', dir: 'asc' },
        badDir: { key: 'gpm', dir: 'sideways' },
      }),
    );
    expect(loadSortByBucket()).toEqual({ ranked: { key: 'gpm', dir: 'asc' } });
  });
  it('returns {} on corrupt JSON', () => {
    localStorage.setItem(SORT_PREF_KEY, 'nope');
    expect(loadSortByBucket()).toEqual({});
  });
});
