import { describe, expect, it } from 'vitest';
import { menuTargetIds } from './row-menu';
import { keepListed, withoutSelected } from './row-selection';

const listed = (...ids: number[]) => {
  const set = new Set(ids);
  return (id: number): boolean => set.has(id);
};

// A Ctrl-click: toggles one row in or out of the selection.
const toggle = (selection: ReadonlySet<number>, id: number): ReadonlySet<number> => {
  const next = new Set(selection);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  return next;
};

describe('keepListed', () => {
  it('drops the selected rows that left the list', () => {
    expect([...keepListed(new Set([7, 8, 9]), listed(8, 9, 10))]).toEqual([8, 9]);
    expect([...keepListed(new Set([7]), listed(8))]).toEqual([]);
  });

  it('keeps the very same set when every selected row is still listed', () => {
    const selection = new Set([7, 8]);
    expect(keepListed(selection, listed(7, 8, 9))).toBe(selection);
    const none = new Set<number>();
    expect(keepListed(none, listed())).toBe(none);
  });

  it('keeps a deleted row out of the next bulk delete once a new row gets its id', () => {
    // Clip 12, the newest, is selected and deleted on its own from its menu, so it leaves the list.
    let selection: ReadonlySet<number> = new Set([12]);
    selection = keepListed(selection, listed(3, 4));
    // A clip cut next takes id 12, and the user Ctrl-clicks 3 and 4 and right-clicks 3.
    selection = keepListed(selection, listed(3, 4, 12));
    selection = toggle(toggle(selection, 3), 4);

    expect(menuTargetIds(selection, 3, listed(3, 4, 12))).toEqual([3, 4]);
  });
});

describe('withoutSelected', () => {
  it('takes the row out of the selection', () => {
    expect([...withoutSelected(new Set([7, 8]), 7)]).toEqual([8]);
    expect([...withoutSelected(new Set([7]), 7)]).toEqual([]);
  });

  it('keeps the very same set when the row was not selected', () => {
    const selection = new Set([8]);
    expect(withoutSelected(selection, 7)).toBe(selection);
  });
});
