import { describe, expect, it } from 'vitest';
import {
  deleteMenuLabel,
  deleteBlockedLabel,
  clipDeleteMenuLabel,
  deleteFailureMessage,
} from './delete-labels';

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

describe('clipDeleteMenuLabel', () => {
  it('a single clip gets a plain delete label', () => {
    expect(clipDeleteMenuLabel(1)).toEqual({
      label: 'Delete clip',
      armedLabel: 'Click to confirm delete',
    });
  });

  it('a bulk selection carries the count in both labels', () => {
    expect(clipDeleteMenuLabel(3)).toEqual({
      label: 'Delete 3 clips',
      armedLabel: 'Click to confirm delete (3)',
    });
  });
});

describe('deleteBlockedLabel', () => {
  it('points a single already-videoless stub at deleting its clips', () => {
    expect(deleteBlockedLabel(1, 2)).toBe('Delete its 2 clips first');
  });

  it('a single clip is not pluralized', () => {
    expect(deleteBlockedLabel(1, 1)).toBe('Delete its 1 clip first');
  });

  it('a bulk all-stub selection uses their', () => {
    expect(deleteBlockedLabel(3, 7)).toBe('Delete their 7 clips first');
  });
});

describe('deleteFailureMessage', () => {
  it('says a single delete did not happen, and why', () => {
    expect(deleteFailureMessage('recording', 1, 1, new Error('No match 7'))).toBe(
      'The recording was not deleted: No match 7',
    );
    expect(deleteFailureMessage('clip', 1, 1, new TypeError('Failed to fetch'))).toBe(
      'The clip was not deleted: Failed to fetch',
    );
  });

  it('counts the rows of a bulk delete that did not go, with the first reason', () => {
    expect(deleteFailureMessage('recording', 2, 5, new Error('locked'))).toBe(
      '2 of 5 recordings were not deleted: locked',
    );
    expect(deleteFailureMessage('clip', 1, 3, new Error('locked'))).toBe(
      '1 of 3 clips was not deleted: locked',
    );
  });

  it('ends the sentence when there is no reason to give', () => {
    expect(deleteFailureMessage('clip', 1, 1, new Error(' '))).toBe('The clip was not deleted.');
    expect(deleteFailureMessage('recording', 1, 1, null)).toBe('The recording was not deleted.');
  });
});
