import { describe, expect, it } from 'vitest';
import { deleteMenuOptions } from './delete-labels';

describe('deleteMenuOptions', () => {
  it('a clipless recording gets one plain full-delete option', () => {
    expect(deleteMenuOptions(1, 0)).toEqual([
      { kind: 'full', label: 'Delete recording', armedLabel: 'Click to confirm delete' },
    ]);
  });

  it('a recording with clips gets keep-clips FIRST, then the explicit full delete', () => {
    expect(deleteMenuOptions(1, 3)).toEqual([
      {
        kind: 'keep',
        label: 'Delete recording, keep 3 clips',
        armedLabel: 'Click to confirm delete',
      },
      {
        kind: 'full',
        label: 'Delete recording + 3 clips',
        armedLabel: 'Click to confirm delete all',
      },
    ]);
  });

  it('a single clip is not pluralized', () => {
    const [keep, full] = deleteMenuOptions(1, 1);
    expect(keep!.label).toBe('Delete recording, keep 1 clip');
    expect(full!.label).toBe('Delete recording + 1 clip');
  });

  it('a clipless bulk selection gets one option with the count in both labels', () => {
    expect(deleteMenuOptions(4, 0)).toEqual([
      { kind: 'full', label: 'Delete 4 recordings', armedLabel: 'Click to confirm delete (4)' },
    ]);
  });

  it('a bulk selection with clips spells out both fates', () => {
    expect(deleteMenuOptions(2, 5)).toEqual([
      {
        kind: 'keep',
        label: 'Delete 2 recordings, keep 5 clips',
        armedLabel: 'Click to confirm delete (2)',
      },
      {
        kind: 'full',
        label: 'Delete 2 recordings + 5 clips',
        armedLabel: 'Click to confirm delete all (2)',
      },
    ]);
  });
});
