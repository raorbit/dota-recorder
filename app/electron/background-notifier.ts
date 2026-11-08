// The shell's two background notifications, driven by the /status poll main.ts already runs every
// few seconds. Pure (injected clock + notify effect) so the debounces unit-test in Node; main.ts
// wires `notify` to an Electron Notification whose click brings the window back.
//
// These used to live in the renderer, which only worked because a window closed to the tray was
// hidden and kept running. The window is destroyed now, so the main process — the one part of the
// app that is always alive — owns them, and owns them alone (no duplicate toast while a window is
// open: the renderer keeps just the in-app banner).
import type { StatusReading } from './status-reading';
import {
  INITIAL_ORPHAN_NOTIFY_STATE,
  isOrphanedRecording,
  shouldNotifyDisk,
  stepOrphanNotify,
  type OrphanNotifyState,
} from './notify-policy';

export type BackgroundNotificationKind = 'orphan' | 'disk';

export interface BackgroundNotifierDeps {
  /** Show a notification of this kind, replacing the previous one of the same kind. */
  notify(kind: BackgroundNotificationKind, title: string, body: string): void;
  /** Wall-clock ms. */
  now(): number;
}

export const ORPHAN_TITLE = 'OBS is still recording';
export const ORPHAN_BODY =
  'A recording is running that the recorder has lost track of. Open Dota 2 Recorder to stop it.';
export const LOW_DISK_TITLE = 'Low disk space';

/** "12.3 GB free on the video drive. …" — the core's raw message embeds bare byte counts. */
export function lowDiskBody(freeBytes: number): string {
  const gb = freeBytes / 1_000_000_000;
  const free = gb >= 10 ? Math.round(gb).toString() : (Math.round(gb * 10) / 10).toString();
  return `${free} GB free on the video drive. Recording continues; the oldest unstarred recordings are pruned to make room.`;
}

export class BackgroundNotifier {
  private orphan: OrphanNotifyState = INITIAL_ORPHAN_NOTIFY_STATE;
  // The observation the last disk decision was made on, so a poll that still sees the same
  // observation (the core re-reports it until the next hourly check) never re-notifies.
  private lastDiskObservedAtMs: number | null = null;
  private lastDiskNotifiedAt: number | null = null;

  constructor(private readonly deps: BackgroundNotifierDeps) {}

  /** Feed one /status reading; null when the core did not answer usably. */
  onStatus(reading: StatusReading | null): void {
    const now = this.deps.now();

    // Orphaned recording: one-shot per streak, after it has held ORPHAN_NOTIFY_DELAY_MS. A null
    // reading resets the streak, as the renderer did when its socket dropped: unknown is not orphaned.
    const step = stepOrphanNotify(this.orphan, isOrphanedRecording(reading), now);
    this.orphan = step.next;
    if (step.fire) this.deps.notify('orphan', ORPHAN_TITLE, ORPHAN_BODY);

    // Low disk: react to a NEW observation only, then rate-limit.
    const lowDisk = reading?.lowDisk ?? null;
    if (lowDisk === null || lowDisk.observedAtMs === this.lastDiskObservedAtMs) return;
    this.lastDiskObservedAtMs = lowDisk.observedAtMs;
    if (!shouldNotifyDisk(this.lastDiskNotifiedAt, now)) return;
    this.lastDiskNotifiedAt = now;
    this.deps.notify('disk', LOW_DISK_TITLE, lowDiskBody(lowDisk.freeBytes));
  }
}
