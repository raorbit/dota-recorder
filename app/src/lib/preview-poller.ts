// Drives the settings scene-preview poll: one GET /obs/preview every PREVIEW_POLL_INTERVAL_MS while
// the page is VISIBLE, nothing while it is hidden. React/DOM-free so it unit-tests in Node;
// useScenePreview wires it to document.visibilityState and React state.
//
// Why visibility matters: every poll renews the core's 30 s OBS demand lease (PreviewController calls
// ObsDemand.touch), so a poll left running in a hidden or minimized window kept OBS wanted for as
// long as the Recording settings tab stayed mounted — the on-demand OBS never stopped. Chromium's
// background throttling only slowed the interval; it never stopped it.

export const PREVIEW_POLL_INTERVAL_MS = 1000;

export interface PreviewPollerDeps<T> {
  /** One GET /obs/preview. A rejection reaches onFrame as `null`. */
  readonly fetchFrame: () => Promise<T>;
  /** Receives each result, or null when the fetch failed. Never called after dispose(). */
  readonly onFrame: (frame: T | null) => void;
  /** Timer primitives, injected so tests drive ticks by hand. */
  readonly setInterval: (fn: () => void, ms: number) => unknown;
  readonly clearInterval: (handle: unknown) => void;
  readonly intervalMs?: number;
}

export interface PreviewPoller {
  /**
   * `true` starts polling (an immediate fetch, then the interval); `false` stops it. Idempotent in
   * both directions, so a visibilitychange storm can't stack intervals.
   */
  setVisible(visible: boolean): void;
  /** Stops polling for good; a fetch still in flight has its result dropped. */
  dispose(): void;
}

export function createPreviewPoller<T>(deps: PreviewPollerDeps<T>): PreviewPoller {
  const intervalMs = deps.intervalMs ?? PREVIEW_POLL_INTERVAL_MS;
  let handle: unknown = null;
  let disposed = false;
  let inFlight = false;

  const tick = async (): Promise<void> => {
    // Only poll while armed (visible and not disposed), and skip a tick while the previous fetch is
    // still in flight, so a slow or contended OBS screenshot (up to the fetch timeout) can't stack
    // overlapping requests on the fixed interval.
    if (handle === null || inFlight || disposed) return;
    inFlight = true;
    try {
      const frame = await deps.fetchFrame();
      if (!disposed) deps.onFrame(frame);
    } catch {
      if (!disposed) deps.onFrame(null);
    } finally {
      inFlight = false;
    }
  };

  const stop = (): void => {
    if (handle !== null) {
      deps.clearInterval(handle);
      handle = null;
    }
  };

  return {
    setVisible(visible) {
      if (disposed) return;
      if (!visible) {
        stop();
        return;
      }
      if (handle !== null) return;
      // Arm before the first tick: tick() treats an unarmed poller as stopped.
      handle = deps.setInterval(() => void tick(), intervalMs);
      void tick();
    },
    dispose() {
      disposed = true;
      stop();
    },
  };
}
