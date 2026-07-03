import { describe, expect, it } from 'vitest';
import { clipLabel } from './clip-format';
import type { Clip } from '../api/client';

// Minimal Clip factory: only the fields clipLabel reads matter; the rest are filler.
function clip(over: Partial<Clip>): Clip {
  return {
    id: 1,
    parentMatchId: 1,
    kind: 'manual',
    triggerReason: null,
    startOffsetS: 0,
    endOffsetS: 10,
    label: null,
    videoPath: null,
    thumbPath: null,
    fileSizeBytes: null,
    status: 'ready',
    error: null,
    createdAt: 0,
    starred: false,
    ...over,
  };
}

describe('clipLabel', () => {
  it('uses the explicit label when present', () => {
    expect(clipLabel(clip({ label: 'First Blood' }))).toBe('First Blood');
  });

  it('ignores a blank/whitespace label and falls back', () => {
    expect(clipLabel(clip({ label: '   ', kind: 'manual' }))).toBe('Manual');
  });

  it('names a rampage auto clip', () => {
    expect(clipLabel(clip({ kind: 'auto', triggerReason: 'rampage' }))).toBe('Rampage');
  });

  it('passes an unknown auto trigger reason through as-is', () => {
    expect(clipLabel(clip({ kind: 'auto', triggerReason: 'ultra_kill' }))).toBe('ultra_kill');
  });

  it('falls back to Auto for an auto clip with no trigger reason', () => {
    expect(clipLabel(clip({ kind: 'auto', triggerReason: null }))).toBe('Auto');
  });

  it('labels a manual clip Manual', () => {
    expect(clipLabel(clip({ kind: 'manual' }))).toBe('Manual');
  });
});
