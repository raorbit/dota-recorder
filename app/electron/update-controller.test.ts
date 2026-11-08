import { describe, expect, it, vi } from 'vitest';
import {
  UpdateController,
  type BusyReason,
  type Cancel,
  type UpdateControllerDeps,
} from './update-controller';
import type { UpdateState } from './bridge-contract';

// A controllable scheduler: schedule() records a task and returns a cancel; the harness
// fires the most-recently-armed task on demand, so tests drive the check/retry cadence
// deterministically without real timers.
interface Scheduled {
  fn: () => void;
  ms: number;
  cancelled: boolean;
  // Set once the harness has fired it: a real timer is spent after firing, so a fired task is no
  // longer "live" even though nothing cancelled it.
  fired: boolean;
}

// Idle-install cadence used by every harness: a 100 ms idle check, a 1000 ms post-download grace,
// two consecutive idle checks.
const IDLE_CHECK_MS = 100;
const GRACE_MS = 1000;

function harness(opts?: {
  busy?: boolean;
  reason?: BusyReason;
  enabled?: boolean;
  idle?: boolean;
}): {
  controller: UpdateController;
  deps: {
    [K in keyof UpdateControllerDeps]: ReturnType<typeof vi.fn> & UpdateControllerDeps[K];
  };
  states: UpdateState[];
  tasks: Scheduled[];
  fireLast: () => void;
  fireIdleCheck: () => Promise<void>;
  /** Idle-check ticks armed and neither cancelled nor fired yet. */
  liveIdleChecks: () => number;
  setBusy: (b: boolean) => void;
  setEnabled: (e: boolean) => void;
  setIdle: (i: boolean) => void;
  advance: (ms: number) => void;
} {
  const states: UpdateState[] = [];
  const tasks: Scheduled[] = [];
  let busy = opts?.busy ?? false;
  const reason: BusyReason = opts?.reason ?? 'recording';
  let enabled = opts?.enabled ?? true;
  let idle = opts?.idle ?? false;
  let now = 1_000_000;

  const deps = {
    triggerCheck: vi.fn(),
    triggerDownload: vi.fn(),
    // Mirror the real gate: a busy result carries a reason ('recording' by default; pass
    // reason:'unreachable' to simulate a down core), an idle result carries none.
    isBusy: vi.fn(async () => (busy ? { busy: true, reason } : { busy: false })),
    isIdleForInstall: vi.fn(async () => idle),
    doInstall: vi.fn(),
    emit: vi.fn((s: UpdateState) => {
      states.push(s);
    }),
    log: vi.fn(),
    isEnabled: vi.fn(() => enabled),
    schedule: vi.fn((fn: () => void, ms: number): Cancel => {
      const task: Scheduled = { fn, ms, cancelled: false, fired: false };
      tasks.push(task);
      return () => {
        task.cancelled = true;
      };
    }),
    now: vi.fn(() => now),
  } as unknown as {
    [K in keyof UpdateControllerDeps]: ReturnType<typeof vi.fn> & UpdateControllerDeps[K];
  };

  const controller = new UpdateController(deps, {
    firstCheckMs: 1000,
    periodicMs: 5000,
    busyRetryMs: 2000,
    idleCheckMs: IDLE_CHECK_MS,
    autoInstallGraceMs: GRACE_MS,
    autoInstallIdleChecks: 2,
  });

  return {
    controller,
    deps,
    states,
    tasks,
    fireLast: () => {
      const live = [...tasks].reverse().find((t) => !t.cancelled && !t.fired);
      if (live) {
        live.fired = true;
        live.fn();
      }
    },
    // Fire the live idle-check tick (the only task armed at IDLE_CHECK_MS) and let its awaits settle.
    fireIdleCheck: async () => {
      const live = [...tasks]
        .reverse()
        .find((t) => !t.cancelled && !t.fired && t.ms === IDLE_CHECK_MS);
      if (live) {
        live.fired = true;
        live.fn();
      }
      await flush();
    },
    liveIdleChecks: () =>
      tasks.filter((t) => t.ms === IDLE_CHECK_MS && !t.cancelled && !t.fired).length,
    setBusy: (b) => {
      busy = b;
    },
    setEnabled: (e) => {
      enabled = e;
    },
    setIdle: (i) => {
      idle = i;
    },
    advance: (ms) => {
      now += ms;
    },
  };
}

// Flush pending microtasks (the await isBusy() chains inside tryDownload/installNow).
const flush = (): Promise<void> => new Promise((r) => setTimeout(r, 0));

describe('UpdateController', () => {
  it('starts idle and reports its snapshot', () => {
    const { controller } = harness();
    expect(controller.getState()).toEqual({ status: 'idle' });
  });

  it('arms a first check on start(), then re-arms at the periodic cadence', () => {
    const { controller, deps, tasks } = harness();
    controller.start();
    expect(deps.schedule).toHaveBeenCalledTimes(1);
    expect(tasks[0].ms).toBe(1000);
    // start() is idempotent.
    controller.start();
    expect(deps.schedule).toHaveBeenCalledTimes(1);
  });

  it('scheduled check respects the enabled pref; manual check() always runs', () => {
    const h = harness({ enabled: false });
    h.controller.start();
    h.fireLast(); // first scheduled check fires while disabled
    expect(h.deps.triggerCheck).not.toHaveBeenCalled();
    // ...but it still re-arms the periodic check.
    expect(h.tasks.some((t) => t.ms === 5000)).toBe(true);
    // A manual check bypasses the pref.
    h.controller.check();
    expect(h.deps.triggerCheck).toHaveBeenCalledTimes(1);
    expect(h.controller.getState().status).toBe('checking');
  });

  it('check() emits checking and fires triggerCheck', () => {
    const { controller, deps, states } = harness();
    controller.check();
    expect(deps.triggerCheck).toHaveBeenCalledTimes(1);
    expect(states.at(-1)).toEqual({ status: 'checking' });
  });

  it('check() does not disturb an in-flight download or a ready update', () => {
    const { controller, deps } = harness();
    controller.onUpdateAvailable('1.2.0', 'url'); // enabled+idle -> begins download path
    // Force into downloading via a progress event is not valid until download starts; instead
    // drive the terminal states directly:
    controller.onUpdateDownloaded('1.2.0');
    deps.triggerCheck.mockClear();
    const s = controller.check();
    expect(deps.triggerCheck).not.toHaveBeenCalled();
    expect(s.status).toBe('downloaded');
  });

  it('auto-downloads an available update when enabled and idle', async () => {
    const { controller, deps, states } = harness({ enabled: true, busy: false });
    controller.onUpdateAvailable('2.0.0', 'https://notes');
    await flush();
    expect(states.some((s) => s.status === 'available' && s.version === '2.0.0')).toBe(true);
    expect(deps.triggerDownload).toHaveBeenCalledTimes(1);
    expect(controller.getState()).toMatchObject({ status: 'downloading', version: '2.0.0', percent: 0 });
  });

  it('does not auto-download when the pref is disabled', async () => {
    const { controller, deps } = harness({ enabled: false });
    controller.onUpdateAvailable('2.0.0', 'url');
    await flush();
    expect(deps.triggerDownload).not.toHaveBeenCalled();
    expect(controller.getState().status).toBe('available');
  });

  it('defers the download while recording and retries after busyRetryMs', async () => {
    const h = harness({ enabled: true, busy: true });
    h.controller.onUpdateAvailable('3.1.0', 'url');
    await flush();
    // Deferred: no download, recording flagged, a retry scheduled at 2000ms.
    expect(h.deps.triggerDownload).not.toHaveBeenCalled();
    expect(h.controller.getState()).toMatchObject({ status: 'available', recording: true });
    const retry = h.tasks.find((t) => t.ms === 2000);
    expect(retry).toBeDefined();
    // Recording ends; firing the retry downloads.
    h.setBusy(false);
    retry!.fn();
    await flush();
    expect(h.deps.triggerDownload).toHaveBeenCalledTimes(1);
    expect(h.controller.getState().status).toBe('downloading');
  });

  it('tracks download progress only while downloading', async () => {
    const h = harness();
    h.controller.onUpdateAvailable('1.0.1', 'url');
    await flush();
    h.controller.onDownloadProgress(42);
    expect(h.controller.getState()).toMatchObject({ status: 'downloading', percent: 42 });
    // Progress after the terminal downloaded state is ignored.
    h.controller.onUpdateDownloaded('1.0.1');
    h.controller.onDownloadProgress(99);
    expect(h.controller.getState().status).toBe('downloaded');
  });

  it('installNow is a no-op unless an update is downloaded', async () => {
    const { controller, deps } = harness();
    await controller.installNow();
    expect(deps.doInstall).not.toHaveBeenCalled();
    expect(deps.isBusy).not.toHaveBeenCalled();
  });

  it('installNow refuses (recording:true) while a match is recording and never installs', async () => {
    const h = harness({ busy: true });
    h.controller.onUpdateDownloaded('4.0.0');
    await h.controller.installNow();
    expect(h.deps.doInstall).not.toHaveBeenCalled();
    expect(h.controller.getState()).toMatchObject({ status: 'downloaded', recording: true });
  });

  it('installNow installs when idle', async () => {
    const h = harness({ busy: false });
    h.controller.onUpdateDownloaded('4.0.0');
    await h.controller.installNow();
    expect(h.deps.doInstall).toHaveBeenCalledTimes(1);
    expect(h.deps.doInstall).toHaveBeenCalledWith('manual');
  });

  describe('unattended install while idle in the tray', () => {
    it('arms an idle check on download and waits out the grace period without asking', async () => {
      const h = harness({ idle: true });
      h.controller.onUpdateDownloaded('5.0.0');
      expect(h.liveIdleChecks()).toBe(1);
      h.advance(GRACE_MS - 1);
      await h.fireIdleCheck();
      expect(h.deps.isIdleForInstall).not.toHaveBeenCalled();
      expect(h.deps.doInstall).not.toHaveBeenCalled();
      // Re-armed for the next tick.
      expect(h.liveIdleChecks()).toBe(1);
    });

    it('installs unattended after two consecutive idle checks past the grace period', async () => {
      const h = harness({ idle: true });
      h.controller.onUpdateDownloaded('5.0.0');
      h.advance(GRACE_MS);
      await h.fireIdleCheck();
      expect(h.deps.doInstall).not.toHaveBeenCalled(); // one idle check is not a quiet spell yet
      await h.fireIdleCheck();
      expect(h.deps.doInstall).toHaveBeenCalledTimes(1);
      expect(h.deps.doInstall).toHaveBeenCalledWith('auto');
      expect(h.deps.log).toHaveBeenCalledWith(expect.stringContaining('unattended'));
      // Nothing left ticking once the install is committed.
      expect(h.liveIdleChecks()).toBe(0);
    });

    it('a non-idle check resets the streak', async () => {
      const h = harness({ idle: true });
      h.controller.onUpdateDownloaded('5.0.0');
      h.advance(GRACE_MS);
      await h.fireIdleCheck(); // idle (1)
      h.setIdle(false);
      await h.fireIdleCheck(); // Dota launched: streak back to 0
      h.setIdle(true);
      await h.fireIdleCheck(); // idle (1)
      expect(h.deps.doInstall).not.toHaveBeenCalled();
      await h.fireIdleCheck(); // idle (2)
      expect(h.deps.doInstall).toHaveBeenCalledWith('auto');
    });

    it('never installs unattended while the pref is off, and resumes when it is turned on', async () => {
      const h = harness({ idle: true, enabled: false });
      h.controller.onUpdateDownloaded('5.0.0');
      h.advance(GRACE_MS);
      await h.fireIdleCheck();
      await h.fireIdleCheck();
      await h.fireIdleCheck();
      expect(h.deps.isIdleForInstall).not.toHaveBeenCalled();
      expect(h.deps.doInstall).not.toHaveBeenCalled();
      h.setEnabled(true);
      await h.fireIdleCheck();
      await h.fireIdleCheck();
      expect(h.deps.doInstall).toHaveBeenCalledWith('auto');
    });

    it('treats a rejected idle probe as not idle', async () => {
      const h = harness({ idle: true });
      h.controller.onUpdateDownloaded('5.0.0');
      h.advance(GRACE_MS);
      await h.fireIdleCheck();
      h.deps.isIdleForInstall.mockRejectedValueOnce(new Error('boom'));
      await h.fireIdleCheck(); // streak reset by the rejection
      await h.fireIdleCheck();
      expect(h.deps.doInstall).not.toHaveBeenCalled();
      await h.fireIdleCheck();
      expect(h.deps.doInstall).toHaveBeenCalledTimes(1);
    });

    it('holds back quietly when the recording gate turns busy at the last moment', async () => {
      const h = harness({ idle: true });
      h.controller.onUpdateDownloaded('5.0.0');
      h.advance(GRACE_MS);
      await h.fireIdleCheck();
      h.setBusy(true); // a match armed between the idle probe and the gate
      await h.fireIdleCheck();
      expect(h.deps.doInstall).not.toHaveBeenCalled();
      // No deferral copy for an install nobody asked for, and the watch keeps going.
      expect(h.controller.getState()).toEqual({ status: 'downloaded', version: '5.0.0' });
      expect(h.liveIdleChecks()).toBe(1);
      h.setBusy(false);
      await h.fireIdleCheck();
      await h.fireIdleCheck();
      expect(h.deps.doInstall).toHaveBeenCalledWith('auto');
    });

    it('resumes the watch after an install that did not quit', async () => {
      const h = harness({ idle: true });
      h.controller.onUpdateDownloaded('5.0.0');
      h.advance(GRACE_MS);
      await h.fireIdleCheck();
      await h.fireIdleCheck();
      expect(h.deps.doInstall).toHaveBeenCalledTimes(1);
      h.controller.onInstallAborted('installer missing');
      await h.fireIdleCheck();
      await h.fireIdleCheck();
      expect(h.deps.doInstall).toHaveBeenCalledTimes(2);
    });

    it('a manual install stops the watch', async () => {
      const h = harness({ idle: true });
      h.controller.onUpdateDownloaded('5.0.0');
      await h.controller.installNow();
      expect(h.deps.doInstall).toHaveBeenCalledWith('manual');
      expect(h.liveIdleChecks()).toBe(0);
    });
  });

  it('surfaces errors', () => {
    const { controller, states } = harness();
    controller.onError('boom');
    expect(states.at(-1)).toEqual({ status: 'error', error: 'boom' });
  });

  it('emits not-available when the check finds nothing', () => {
    const { controller } = harness();
    controller.check();
    controller.onUpdateNotAvailable();
    expect(controller.getState().status).toBe('not-available');
  });

  it('does not download a deferred update after auto-update is disabled mid-defer', async () => {
    const h = harness({ enabled: true, busy: true });
    h.controller.onUpdateAvailable('5.0.0', 'url');
    await flush();
    const retry = h.tasks.find((t) => t.ms === 2000);
    expect(retry).toBeDefined();
    // User disables auto-update while deferred, then the match ends and the retry fires.
    h.setEnabled(false);
    h.setBusy(false);
    retry!.fn();
    await flush();
    expect(h.deps.triggerDownload).not.toHaveBeenCalled();
  });

  it('serializes concurrent download attempts (no double triggerDownload)', async () => {
    const h = harness({ enabled: true, busy: false });
    // Two update-available events back-to-back, before the first isBusy() resolves: the second
    // tryDownload must see the claimed download slot and bail.
    h.controller.onUpdateAvailable('9.0.0', 'url');
    h.controller.onUpdateAvailable('9.0.0', 'url');
    await flush();
    expect(h.deps.triggerDownload).toHaveBeenCalledTimes(1);
  });

  it('serializes concurrent install requests (no double doInstall)', async () => {
    const h = harness({ busy: false });
    h.controller.onUpdateDownloaded('7.0.0');
    await Promise.all([h.controller.installNow(), h.controller.installNow()]);
    expect(h.deps.doInstall).toHaveBeenCalledTimes(1);
  });

  it('releases the install latch (and does not install) when the recording gate check throws', async () => {
    const h = harness({ busy: false });
    h.controller.onUpdateDownloaded('7.0.0');
    // A rejecting isBusy must not wedge the latch: no install this time, and a later click still can.
    h.deps.isBusy.mockRejectedValueOnce(new Error('core unreachable'));
    await h.controller.installNow();
    expect(h.deps.doInstall).not.toHaveBeenCalled();
    await h.controller.installNow();
    expect(h.deps.doInstall).toHaveBeenCalledTimes(1);
  });

  it('clears a deferred-download deferral (and its retry) when auto-update is disabled', async () => {
    const h = harness({ enabled: true, busy: true });
    h.controller.onUpdateAvailable('8.0.0', 'url');
    await flush();
    expect(h.controller.getState()).toMatchObject({ status: 'available', recording: true });
    h.setEnabled(false);
    h.controller.onAutoUpdateChanged(false);
    expect(h.controller.getState().status).toBe('available');
    expect(h.controller.getState().recording).toBeUndefined();
    // The pending retry was cancelled.
    expect(h.tasks.find((t) => t.ms === 2000 && !t.cancelled)).toBeUndefined();
  });

  it('re-checks when auto-update is re-enabled', () => {
    const h = harness({ enabled: false });
    h.controller.onAutoUpdateChanged(true);
    expect(h.deps.triggerCheck).toHaveBeenCalledTimes(1);
  });

  it('clears the install-deferral recording flag once the match ends (without auto-installing)', async () => {
    const h = harness({ busy: true });
    h.controller.onUpdateDownloaded('6.0.0');
    await h.controller.installNow();
    expect(h.controller.getState()).toMatchObject({ status: 'downloaded', recording: true });
    const poll = h.tasks.find((t) => t.ms === 2000);
    expect(poll).toBeDefined();
    // Match ends; the poll drops the flag but does NOT auto-install (user re-confirms).
    h.setBusy(false);
    poll!.fn();
    await flush();
    expect(h.deps.doInstall).not.toHaveBeenCalled();
    expect(h.controller.getState().status).toBe('downloaded');
    expect(h.controller.getState().recording).toBeUndefined();
  });

  it('installNow defers with unreachable (not recording) when the core is down', async () => {
    // A down/hung core fails the gate to busy with reason 'unreachable'; the UI must not blame a match.
    const h = harness({ busy: true, reason: 'unreachable' });
    h.controller.onUpdateDownloaded('4.2.0');
    await h.controller.installNow();
    expect(h.deps.doInstall).not.toHaveBeenCalled();
    expect(h.controller.getState()).toMatchObject({ status: 'downloaded', unreachable: true });
    expect(h.controller.getState().recording).toBeUndefined();
  });

  it('onError keeps a downloaded update installable instead of hiding it', () => {
    const h = harness();
    h.controller.onUpdateDownloaded('5.1.0');
    h.controller.onError('background failure after download');
    // The staged installer is still on disk, so the state stays 'downloaded' (Restart stays visible).
    expect(h.controller.getState().status).toBe('downloaded');
  });

  it('onError still surfaces errors from a non-downloaded state', () => {
    const h = harness();
    h.controller.check();
    h.controller.onError('check failed');
    expect(h.controller.getState()).toMatchObject({ status: 'error', error: 'check failed' });
  });

  it('onInstallAborted releases the latch and leaves the update installable', async () => {
    // Simulates a quitAndInstall that did not quit: after doInstall the latch is set; onInstallAborted
    // must clear it so a later click re-installs, and keep the state 'downloaded'.
    const h = harness({ busy: false });
    h.controller.onUpdateDownloaded('6.3.0');
    await h.controller.installNow();
    expect(h.deps.doInstall).toHaveBeenCalledTimes(1);
    h.controller.onInstallAborted('installer did not run');
    expect(h.controller.getState().status).toBe('downloaded');
    await h.controller.installNow();
    expect(h.deps.doInstall).toHaveBeenCalledTimes(2);
  });
});
