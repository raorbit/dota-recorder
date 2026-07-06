import { describe, expect, it } from 'vitest';
import { deleteMenuLabel } from './delete-labels';

describe('deleteMenuLabel', () => {
  it('a clipless recording gets a plain delete label', () => {
    expect(deleteMenuLabel(1, 0)).toEqual({
      label: 'Delete recording',
      armedLabel: 'Click to confirm delete',
    });
  });

  it('a recording with clips says they are kept (the row survives as a stub)', () => {
    expect(deleteMenuLabel(1, 3)).toEqual({
      label: 'Delete recording (keeps 3 clips)',
      armedLabel: 'Click to confirm delete',
    });
  });

  it('a single clip is not pluralized', () => {
    expect(deleteMenuLabel(1, 1).label).toBe('Delete recording (keeps 1 clip)');
  });

  it('a clipless bulk selection carries the count in both labels', () => {
    expect(deleteMenuLabel(4, 0)).toEqual({
      label: 'Delete 4 recordings',
      armedLabel: 'Click to confirm delete (4)',
    });
  });

  it('a bulk selection with clips says how many are kept', () => {
    expect(deleteMenuLabel(2, 5)).toEqual({
      label: 'Delete 2 recordings (keeps 5 clips)',
      armedLabel: 'Click to confirm delete (2)',
    });
  });
});
