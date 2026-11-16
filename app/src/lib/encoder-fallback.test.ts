import { describe, expect, it } from 'vitest';
import { encoderFallbackNote } from './encoder-fallback';

const LABELS = {
  qsv: 'Intel QuickSync (H.264)',
  x264: 'x264 (software)',
};

describe('encoderFallbackNote', () => {
  it('says Auto is on x264, why, and how to get the hardware encoder back', () => {
    expect(encoderFallbackNote('auto', 'qsv', LABELS)).toBe(
      'Auto is using x264 (software) because Intel QuickSync (H.264) failed to start recording. ' +
        'Restarting the app tries it again.',
    );
  });

  it('says nothing while Auto runs the encoder it picked', () => {
    expect(encoderFallbackNote('auto', null, LABELS)).toBeNull();
  });

  it('says nothing once another encoder is chosen', () => {
    expect(encoderFallbackNote('nvenc', 'qsv', LABELS)).toBeNull();
  });

  it('names an encoder it has no label for by its token', () => {
    expect(encoderFallbackNote('auto', 'jim_av1', {})).toBe(
      'Auto is using x264 because jim_av1 failed to start recording. ' +
        'Restarting the app tries it again.',
    );
  });
});
