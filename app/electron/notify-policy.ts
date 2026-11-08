// Pure policy behind the shell's two background notifications, React/DOM-free so it unit-tests in
// Node. The main process owns these: a window closed to the tray is destroyed, not hidden, so no
// renderer survives to fire them (the renderer keeps only the in-app low-disk banner).
//   - the orphaned-recording debounce: OBS is still writing a file the FSM has let go of, and we want
//     ONE notification, and only after the condition has persisted long enough that a self-healing
//     blip (the core's own bounded stop retries) won't trip it.
//   - the disk-warning debounce: the core's hourly free-space check repeats its observation while the
//     drive stays low, so rate-limit the notification.

/** The two fields of a /status reading the orphan check reads. */
export interface RecorderReading {
  /** OBS reports an active recording output. */
  readonly recording: boolean;
  /** MatchFsm state name: IDLE, ARMED, RECORDING, STOPPING. */
  readonly fsmState: string;
}

// The orphan condition the stop button acts on: OBS reports it is recording while the FSM is NOT in
// its RECORDING state (it finalized/reset but OBS kept writing). A null reading (core unreachable) is
// not an orphan — we don't know the real state, so don't warn.
export function isOrphanedRecording(reading: RecorderReading | null): boolean {
  return reading !== null && reading.recording && reading.fsmState !== 'RECORDING';
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
