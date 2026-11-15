import { describe, expect, it } from 'vitest';
import { createDeleteQueue } from './delete-queue';

// A promise the test settles by hand: a delete request waiting on the core's maintenance lock.
function waiting<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (err: unknown) => void;
} {
  let resolve!: (value: T) => void;
  let reject!: (err: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

// Lets every settled promise's callbacks run.
const settle = (): Promise<void> => new Promise((r) => setTimeout(r, 0));

describe('createDeleteQueue', () => {
  it('sends one delete at a time, in the order they were asked for', async () => {
    const queue = createDeleteQueue();
    const first = waiting<string>();
    const second = waiting<string>();
    const sent: string[] = [];
    const a = queue.run('match:7', () => {
      sent.push('match:7');
      return first.promise;
    });
    const b = queue.run('clip:8', () => {
      sent.push('clip:8');
      return second.promise;
    });

    await settle();
    expect(sent).toEqual(['match:7']);

    first.resolve('seven');
    await expect(a).resolves.toBe('seven');
    await settle();
    expect(sent).toEqual(['match:7', 'clip:8']);

    second.resolve('eight');
    await expect(b).resolves.toBe('eight');
  });

  it('sends the next delete after one that failed, and hands the failure to its caller', async () => {
    const queue = createDeleteQueue();
    const first = waiting<void>();
    let secondSent = false;
    const a = queue.run('match:7', () => first.promise);
    const b = queue.run('match:8', async () => {
      secondSent = true;
    });

    first.reject(new Error('refused'));
    await expect(a).rejects.toThrow('refused');
    await b;
    expect(secondSent).toBe(true);
  });

  it('joins a delete of something already queued or in flight instead of sending it again', async () => {
    const queue = createDeleteQueue();
    const inFlight = waiting<string>();
    const queued = waiting<string>();
    const sent: string[] = [];
    const a = queue.run('match:7', () => {
      sent.push('match:7');
      return inFlight.promise;
    });
    const b = queue.run('clip:8', () => {
      sent.push('clip:8');
      return queued.promise;
    });
    await settle();

    // match:7 is in flight and clip:8 waits its turn: asking again for either sends nothing.
    expect(queue.run('match:7', () => Promise.resolve('again'))).toBe(a);
    expect(queue.run('clip:8', () => Promise.resolve('again'))).toBe(b);

    inFlight.resolve('seven');
    queued.resolve('eight');
    await expect(a).resolves.toBe('seven');
    await expect(b).resolves.toBe('eight');
    expect(sent).toEqual(['match:7', 'clip:8']);
  });

  it('sends a delete of the same thing again once the first has settled', async () => {
    const queue = createDeleteQueue();
    let sends = 0;
    const send = (): Promise<void> => {
      sends++;
      return Promise.reject(new Error('refused'));
    };

    // A caller retrying as soon as it sees the failure gets a fresh request, not the old failure.
    await queue.run('match:7', send).catch(() => queue.run('match:7', send).catch(() => {}));
    expect(sends).toBe(2);
  });
});
