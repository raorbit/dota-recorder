import { describe, expect, it } from 'vitest';
import { menuTargetIds } from './row-menu';

describe('menuTargetIds', () => {
  const listed = (...ids: number[]) => {
    const set = new Set(ids);
    return (id: number): boolean => set.has(id);
  };

  it('acts on the whole selection when the clicked row is part of it', () => {
    expect(menuTargetIds(new Set([7, 8, 9]), 8, listed(7, 8, 9))).toEqual([7, 8, 9]);
  });

  it('acts on just the clicked row when it is outside the selection', () => {
    expect(menuTargetIds(new Set([7, 8]), 9, listed(7, 8, 9))).toEqual([9]);
    expect(menuTargetIds(new Set(), 9, listed(9))).toEqual([9]);
  });

  it('leaves out a selected row that is no longer listed', () => {
    // 7 was deleted on its own from its menu, which kept it selected; then 8 and 9 were Ctrl-clicked.
    expect(menuTargetIds(new Set([7, 8, 9]), 8, listed(8, 9))).toEqual([8, 9]);
  });

  it('acts on nothing once the clicked row is no longer listed', () => {
    // The menu stayed open on clip 50 while its queued delete finished. The clip cut next takes id
    // 50, so the menu must not act on that id any more.
    expect(menuTargetIds(new Set([50]), 50, listed(49))).toEqual([]);
    expect(menuTargetIds(new Set(), 50, listed(49))).toEqual([]);
    expect(menuTargetIds(new Set([7, 8]), 8, listed(7))).toEqual([]);
  });
});
