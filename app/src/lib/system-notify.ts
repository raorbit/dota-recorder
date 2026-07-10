// Pure logic behind the storage-observability notifications the library wiring fires. Two concerns,
// both kept React/DOM-free so they unit-test in plain Node (the actual Notification side effect lives
// in ../store/library.ts):
//   - the orphaned-recording debounce: OBS is still writing a file the FSM has let go of, and we want
//     ONE notification, and only after the condition has persisted long enough that a self-healing
//     blip (e.g. the core's own bounded stop retries, landing this same release) won't trip it.
//   - the disk-warning debounce: low-disk frames can repeat per record attempt, so rate-limit the
//     notification to avoid spamming the user (the banner state is still updated every frame).
import type { Status } from '../api/client';

// The orphan condition the round-3 stop button acts on: OBS reports it is recording while the FSM is
// NOT in its RECORDING state (it finalized/reset but OBS kept writing). A null status (socket down) is
// not an orphan — we don't know the real state, so don't warn.
export function isOrphanedRecording(status: Status | null): boolean {
  return status !== null && status.recording && status.fsmState !== 'RECORDING';
}

// Notify only after the orphan condition has held continuously for this long, so a brief blip the core
// self-heals (its bounded stop retries) doesn't fire a spurious notification.
export const ORPHAN_NOTIFY_DELAY_MS = 60_000;

export interface OrphanNotifyState {
  // Wall-clock ms when the current orphan streak was first observed active, or null when inactive.
  readonly since: number | null;
  // Whether the one-shot notification has already fired for the current active streak.
  readonly notified: boolean;
}

export const INITIAL_ORPHAN_NOTIFY_STATE: OrphanNotifyState = { since: null, notified: false };

// Advance the orphan debounce one step. Given the previous state, whether the condition is active NOW,
// and the current wall-clock ms, returns the next state and whether to FIRE the one-shot this step.
// Firing is one-shot per streak (`notified` latches true until the condition clears) and gated on the
// streak having persisted >= ORPHAN_NOTIFY_DELAY_MS. When the condition clears the state resets, so a
// later orphan re-notifies.
export function stepOrphanNotify(
  prev: OrphanNotifyState,
  active: boolean,
  now: number,
): { readonly next: OrphanNotifyState; readonly fire: boolean } {
  if (!active) return { next: INITIAL_ORPHAN_NOTIFY_STATE, fire: false };
  const since = prev.since ?? now; // start the streak clock on the first active observation
  if (prev.notified) return { next: { since, notified: true }, fire: false };
  if (now - since >= ORPHAN_NOTIFY_DELAY_MS) return { next: { since, notified: true }, fire: true };
  return { next: { since, notified: false }, fire: false };
}

// Rate-limit repeated low-disk notifications: fire only when none has fired yet, or the last one was
// at least this long ago.
export const DISK_NOTIFY_DEBOUNCE_MS = 10 * 60_000;

export function shouldNotifyDisk(lastFiredAt: number | null, now: number): boolean {
  return lastFiredAt === null || now - lastFiredAt >= DISK_NOTIFY_DEBOUNCE_MS;
}
