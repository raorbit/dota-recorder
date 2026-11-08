import { describe, expect, it } from 'vitest';
import { MIN_VISIBLE_PX, restorableBounds } from './window-bounds';

const primary = { x: 0, y: 0, width: 2560, height: 1400 };

describe('restorableBounds', () => {
  it('keeps bounds that sit on the display', () => {
    const saved = { x: 200, y: 100, width: 1280, height: 820 };
    expect(restorableBounds(saved, primary)).toEqual(saved);
  });

  it('keeps bounds that merely spill off an edge', () => {
    const saved = { x: 2400, y: 1300, width: 1280, height: 820 }; // 160 x 100 px still visible
    expect(restorableBounds(saved, primary)).toEqual(saved);
  });

  it('rejects bounds left on a display that is gone', () => {
    // Saved on a monitor to the right that is no longer attached: the best-matching display is the
    // primary one and nothing of the window would land on it.
    expect(restorableBounds({ x: 2600, y: 100, width: 1280, height: 820 }, primary)).toBeNull();
    // Barely visible slivers don't count either.
    const sliver = { x: primary.width - MIN_VISIBLE_PX + 1, y: 0, width: 1280, height: 820 };
    expect(restorableBounds(sliver, primary)).toBeNull();
  });

  it('is null without a saved rectangle or a display', () => {
    expect(restorableBounds(null, primary)).toBeNull();
    expect(restorableBounds({ x: 0, y: 0, width: 10, height: 10 }, null)).toBeNull();
  });
});
