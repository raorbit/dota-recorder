import { describe, expect, it } from 'vitest';
import type { StatusSnapshot } from '../api/client';
import { obsJustConnected, recorderStatusLabel } from './recorder-status';

function obs(overrides: Partial<StatusSnapshot['obs']> = {}): StatusSnapshot['obs'] {
  return {
    connected: true,
    sceneActive: true,
    recording: false,
    captureBlack: false,
    wanted: true,
    ...overrides,
  };
}

describe('recorderStatusLabel', () => {
  it('is unknown before the first status frame', () => {
    expect(recorderStatusLabel(null).state).toBe('unknown');
  });

  it('is on standby when OBS is stopped because nothing needs it', () => {
    expect(recorderStatusLabel(obs({ connected: false, wanted: false }))).toEqual({
      text: 'Recorder: standby',
      state: 'standby',
    });
  });

  it('is starting when OBS is wanted but not connected yet', () => {
    expect(recorderStatusLabel(obs({ connected: false, wanted: true }))).toEqual({
      text: 'Recorder: starting…',
      state: 'preparing',
    });
  });

  it('reports recording, ready and preparing once connected', () => {
    expect(recorderStatusLabel(obs({ recording: true })).state).toBe('recording');
    expect(recorderStatusLabel(obs()).state).toBe('ready');
    expect(recorderStatusLabel(obs({ sceneActive: false })).state).toBe('preparing');
  });
});

describe('obsJustConnected', () => {
  it('fires only on a disconnected-to-connected edge', () => {
    const down = obs({ connected: false });
    const up = obs();
    expect(obsJustConnected(down, up)).toBe(true);
    expect(obsJustConnected(up, up)).toBe(false);
    expect(obsJustConnected(up, down)).toBe(false);
    expect(obsJustConnected(down, down)).toBe(false);
  });

  it('does not fire on the first frame (nothing to compare against)', () => {
    expect(obsJustConnected(null, obs())).toBe(false);
  });
});
