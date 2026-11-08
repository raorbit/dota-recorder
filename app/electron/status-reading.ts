// One parsed GET /status body, as the main process reads it every few seconds. The OBS demand poll
// (wanted/busy) and the background notifier (recording/fsmState/lowDisk) share one fetch and this
// one parser. Pure, so the tolerance rules below are unit-tested.
import type { ObsDemandReading } from './obs-demand';

/** The core's latest low-disk observation (StatusSnapshot.storage.lowDisk). */
export interface LowDiskReading {
  readonly freeBytes: number;
  readonly thresholdBytes: number;
  readonly message: string;
  /** Wall-clock ms of the check that made the observation; a new value means a new observation. */
  readonly observedAtMs: number;
}

export interface StatusReading {
  /** The core wants OBS running (obs.wanted). A core predating on-demand OBS reads as wanted. */
  readonly wanted: boolean;
  /** OBS reports an active recording output (obs.recording). */
  readonly recording: boolean;
  /** MatchFsm state name (fsm.state). */
  readonly fsmState: string;
  /** The latest low-disk observation, or null while the drive is healthy (or on an older core). */
  readonly lowDisk: LowDiskReading | null;
}

/**
 * Parses a /status JSON body. Null when the body says nothing usable about the recorder (no
 * `fsm.state`), which callers treat as "the core didn't answer": leave OBS as it is, reset the
 * orphan streak. Optional fields degrade rather than reject: a missing `obs.wanted` reads as wanted
 * (the pre-on-demand behaviour), a missing or malformed `storage` as no low-disk observation.
 */
export function parseStatusBody(body: unknown): StatusReading | null {
  if (!isRecord(body) || !isRecord(body.fsm) || typeof body.fsm.state !== 'string') return null;
  const obs = isRecord(body.obs) ? body.obs : {};
  return {
    wanted: obs.wanted !== false,
    recording: obs.recording === true,
    fsmState: body.fsm.state,
    lowDisk: parseLowDisk(body.storage),
  };
}

/** The OBS demand view: busy while a match is arming/recording/stopping or OBS has an output. */
export function toObsDemandReading(reading: StatusReading): ObsDemandReading {
  return { wanted: reading.wanted, busy: reading.recording || reading.fsmState !== 'IDLE' };
}

function parseLowDisk(storage: unknown): LowDiskReading | null {
  if (!isRecord(storage) || !isRecord(storage.lowDisk)) return null;
  const { freeBytes, thresholdBytes, message, observedAtMs } = storage.lowDisk;
  if (
    typeof freeBytes !== 'number' ||
    typeof thresholdBytes !== 'number' ||
    typeof message !== 'string' ||
    typeof observedAtMs !== 'number'
  ) {
    return null;
  }
  return { freeBytes, thresholdBytes, message, observedAtMs };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
