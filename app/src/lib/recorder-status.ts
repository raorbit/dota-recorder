import type { StatusSnapshot } from '../api/client';

export type RecorderStatusState =
  | 'unknown'
  | 'error'
  | 'recording'
  | 'ready'
  | 'preparing'
  | 'standby';

export interface RecorderStatus {
  readonly text: string;
  readonly state: RecorderStatusState;
}

// Single self-describing "Recorder" indicator. The recorder is app-managed now,
// so we deliberately avoid any OBS jargon; the prefix makes the chip readable on
// its own. Evaluated top-down, first match wins — connected is gated before the
// recording check so a dropped backend reads as an error, not a stale "recording".
// OBS runs only on demand, so a disconnected OBS nothing needs is on standby, and one
// that is wanted but not connected yet is still starting.
export function recorderStatusLabel(obs: StatusSnapshot['obs'] | null): RecorderStatus {
  if (obs === null) return { text: 'Recorder: connecting…', state: 'unknown' };
  if (!obs.connected) {
    return obs.wanted
      ? { text: 'Recorder: starting…', state: 'preparing' }
      : { text: 'Recorder: standby', state: 'standby' };
  }
  if (obs.recording) return { text: 'Recorder: recording', state: 'recording' };
  if (obs.sceneActive) return { text: 'Recorder: ready', state: 'ready' };
  return { text: 'Recorder: preparing', state: 'preparing' };
}

/**
 * Whether OBS just came up (a disconnected-to-connected edge), so views that read from it once,
 * like the audio pickers, should load again. OBS starts on demand, often after the view opened.
 */
export function obsJustConnected(
  previous: StatusSnapshot['obs'] | null,
  current: StatusSnapshot['obs'] | null,
): boolean {
  return previous !== null && !previous.connected && current?.connected === true;
}
