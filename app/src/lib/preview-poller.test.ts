import { describe, expect, it, vi } from 'vitest';
import { createPreviewPoller, PREVIEW_POLL_INTERVAL_MS } from './preview-poller';

interface Frame {
  readonly dataUri: string | null;
}

/** A poller over hand-driven timers and a controllable fetch. */
function harness(fetchImpl?: () => Promise<Frame>) {
  const intervals: Array<{ fn: () => void; ms: number; handle: object }> = [];
  const cleared: object[] = [];
  const onFrame = vi.fn<(frame: Frame | null) => void>();
  const fetchFrame = vi.fn(fetchImpl ?? (async () => ({ dataUri: 'data:image/jpeg;base64,x' })));
  const poller = createPreviewPoller<Frame>({
    fetchFrame,
    onFrame,
    setInterval: (fn, ms) => {
      const handle = {};
      intervals.push({ fn, ms, handle });
      return handle;
    },
    clearInterval: (handle) => cleared.push(handle as object),
  });
  const live = (): number => intervals.filter((i) => !cleared.includes(i.handle)).length;
  return { poller, intervals, cleared, onFrame, fetchFrame, live };
}

const flush = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

describe('createPreviewPoller', () => {
  it('fetches at once and arms the interval when the page becomes visible', async () => {
    const h = harness();
    h.poller.setVisible(true);
    await flush();
    expect(h.fetchFrame).toHaveBeenCalledTimes(1);
    expect(h.onFrame).toHaveBeenCalledWith({ dataUri: 'data:image/jpeg;base64,x' });
    expect(h.intervals).toHaveLength(1);
    expect(h.intervals[0].ms).toBe(PREVIEW_POLL_INTERVAL_MS);
  });

  it('stops polling while hidden and resumes with a fresh fetch when visible again', async () => {
    const h = harness();
    h.poller.setVisible(true);
    await flush();
    h.poller.setVisible(false);
    expect(h.live()).toBe(0);
    // A tick that would have fired from the (now cleared) interval must not fetch: the hidden
    // window must not keep renewing the core's OBS lease.
    h.intervals[0].fn();
    await flush();
    expect(h.fetchFrame).toHaveBeenCalledTimes(1);

    h.poller.setVisible(true);
    await flush();
    expect(h.fetchFrame).toHaveBeenCalledTimes(2);
    expect(h.live()).toBe(1);
  });

  it('is idempotent: repeated visible/hidden signals never stack or double-clear intervals', async () => {
    const h = harness();
    h.poller.setVisible(true);
    h.poller.setVisible(true);
    await flush();
    expect(h.intervals).toHaveLength(1);
    expect(h.fetchFrame).toHaveBeenCalledTimes(1);
    h.poller.setVisible(false);
    h.poller.setVisible(false);
    expect(h.cleared).toHaveLength(1);
  });

  it('skips a tick while the previous fetch is still in flight', async () => {
    let resolveFetch: (frame: Frame) => void = () => {};
    const h = harness(() => new Promise<Frame>((resolve) => (resolveFetch = resolve)));
    h.poller.setVisible(true);
    h.intervals[0].fn();
    h.intervals[0].fn();
    expect(h.fetchFrame).toHaveBeenCalledTimes(1);
    resolveFetch({ dataUri: null });
    await flush();
    h.intervals[0].fn();
    expect(h.fetchFrame).toHaveBeenCalledTimes(2);
  });

  it('reports a failed fetch as a null frame', async () => {
    const h = harness(async () => {
      throw new Error('OBS down');
    });
    h.poller.setVisible(true);
    await flush();
    expect(h.onFrame).toHaveBeenCalledWith(null);
  });

  it('drops a result that lands after dispose and ignores later visibility signals', async () => {
    let resolveFetch: (frame: Frame) => void = () => {};
    const h = harness(() => new Promise<Frame>((resolve) => (resolveFetch = resolve)));
    h.poller.setVisible(true);
    h.poller.dispose();
    expect(h.live()).toBe(0);
    resolveFetch({ dataUri: 'late' });
    await flush();
    expect(h.onFrame).not.toHaveBeenCalled();
    h.poller.setVisible(true);
    expect(h.intervals).toHaveLength(1); // no new interval after dispose
  });
});
