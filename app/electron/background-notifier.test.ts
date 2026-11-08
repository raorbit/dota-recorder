import { describe, expect, it, vi } from 'vitest';
import {
  BackgroundNotifier,
  LOW_DISK_TITLE,
  ORPHAN_TITLE,
  lowDiskBody,
} from './background-notifier';
import { DISK_NOTIFY_DEBOUNCE_MS, ORPHAN_NOTIFY_DELAY_MS } from './notify-policy';
import type { StatusReading } from './status-reading';

const POLL_MS = 3_000;

function harness() {
  let now = 1_000_000;
  const notify = vi.fn<(kind: 'orphan' | 'disk', title: string, body: string) => void>();
  const notifier = new BackgroundNotifier({ notify, now: () => now });
  /** Feed `reading` once per poll for `ms` of wall-clock time. */
  const pollFor = (ms: number, reading: StatusReading | null): void => {
    for (let elapsed = 0; elapsed < ms; elapsed += POLL_MS) {
      now += POLL_MS;
      notifier.onStatus(reading);
    }
  };
  const advance = (ms: number): void => {
    now += ms;
  };
  return { notifier, notify, pollFor, advance };
}

const idle: StatusReading = { wanted: false, recording: false, fsmState: 'IDLE', lowDisk: null };
const orphaned: StatusReading = { ...idle, recording: true };
const liveMatch: StatusReading = { ...idle, wanted: true, recording: true, fsmState: 'RECORDING' };
const lowDisk = (observedAtMs: number): StatusReading => ({
  ...idle,
  lowDisk: { freeBytes: 2_500_000_000, thresholdBytes: 5_000_000_000, message: 'raw', observedAtMs },
});

describe('BackgroundNotifier: orphaned recording', () => {
  it('fires exactly once after the condition has held for the delay, across many polls', () => {
    const h = harness();
    h.pollFor(ORPHAN_NOTIFY_DELAY_MS - POLL_MS, orphaned);
    expect(h.notify).not.toHaveBeenCalled();
    h.pollFor(10 * 60_000, orphaned);
    expect(h.notify).toHaveBeenCalledTimes(1);
    expect(h.notify).toHaveBeenCalledWith('orphan', ORPHAN_TITLE, expect.stringContaining('Open'));
  });

  it('never fires for a normal recording', () => {
    const h = harness();
    h.pollFor(60 * 60_000, liveMatch);
    expect(h.notify).not.toHaveBeenCalled();
  });

  it('resets the streak when the condition clears or the core stops answering, then re-arms', () => {
    const h = harness();
    h.pollFor(ORPHAN_NOTIFY_DELAY_MS - POLL_MS, orphaned);
    h.pollFor(POLL_MS, null); // core unreachable: unknown is not orphaned
    h.pollFor(ORPHAN_NOTIFY_DELAY_MS - POLL_MS, orphaned);
    expect(h.notify).not.toHaveBeenCalled(); // the streak restarted at the null reading

    h.pollFor(2 * POLL_MS, orphaned);
    expect(h.notify).toHaveBeenCalledTimes(1);

    h.pollFor(POLL_MS, idle); // healed
    h.pollFor(ORPHAN_NOTIFY_DELAY_MS + POLL_MS, orphaned); // a later orphan notifies again
    expect(h.notify).toHaveBeenCalledTimes(2);
  });
});

describe('BackgroundNotifier: low disk', () => {
  it('notifies once for a new observation and stays silent while polls repeat it', () => {
    const h = harness();
    h.pollFor(30 * 60_000, lowDisk(1));
    expect(h.notify).toHaveBeenCalledTimes(1);
    expect(h.notify).toHaveBeenCalledWith('disk', LOW_DISK_TITLE, expect.stringContaining('2.5 GB'));
  });

  it('rate-limits a new observation inside the debounce window and notifies after it', () => {
    const h = harness();
    h.pollFor(POLL_MS, lowDisk(1));
    h.pollFor(POLL_MS, lowDisk(2)); // a new check, seconds later: suppressed
    expect(h.notify).toHaveBeenCalledTimes(1);
    h.advance(DISK_NOTIFY_DEBOUNCE_MS);
    h.pollFor(POLL_MS, lowDisk(3)); // the next hourly check: notifies again
    expect(h.notify).toHaveBeenCalledTimes(2);
  });

  it('does nothing while healthy, and a recovery followed by a new low observation notifies', () => {
    const h = harness();
    h.pollFor(60 * 60_000, idle);
    expect(h.notify).not.toHaveBeenCalled();
    h.pollFor(POLL_MS, lowDisk(1));
    h.pollFor(POLL_MS, idle); // recovered
    h.advance(DISK_NOTIFY_DEBOUNCE_MS);
    h.pollFor(POLL_MS, lowDisk(2));
    expect(h.notify).toHaveBeenCalledTimes(2);
  });

  it('formats the free space for people, not the core log', () => {
    expect(lowDiskBody(2_500_000_000)).toMatch(/^2\.5 GB free/);
    expect(lowDiskBody(48_200_000_000)).toMatch(/^48 GB free/);
    expect(lowDiskBody(0)).toMatch(/^0 GB free/);
  });
});
