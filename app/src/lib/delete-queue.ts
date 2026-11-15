// The queue the renderer's recording and clip deletes go through, one request at a time. A delete
// waits for the core's storage-maintenance lock, which an archive pass holds through all its
// cross-drive copies (minutes for multi-GB VODs onto a slow drive), and a waiting request keeps its
// connection to the core open the whole time. Chromium opens at most six HTTP/1.1 connections to one
// host, so six deletes parked behind one pass would leave every other request to the core waiting for
// a connection until it timed out. Queued, the deletes hold one connection between them. Kept free of
// fetch and React so it unit-tests in plain Node (see delete-queue.test.ts).

export interface DeleteQueue {
  /**
   * Calls `send` once every delete queued before it has settled, and settles as that call does. A
   * call whose `key` names a delete still queued or in flight gets that delete's promise instead and
   * sends nothing, so asking twice for one row's delete sends one request. Keys name what is deleted
   * (`match:7`, `clip:7`), so the promise a key joins is always of the type its caller expects.
   */
  run<T>(key: string, send: () => Promise<T>): Promise<T>;
}

export function createDeleteQueue(): DeleteQueue {
  // The delete each key names, while it is queued or in flight.
  const pending = new Map<string, Promise<unknown>>();
  // Settles once the last delete queued so far has, whichever way: the next one starts then.
  let tail: Promise<unknown> = Promise.resolve();
  return {
    run<T>(key: string, send: () => Promise<T>): Promise<T> {
      const joined = pending.get(key);
      if (joined !== undefined) return joined as Promise<T>;
      const result = tail.then(async () => {
        try {
          return await send();
        } finally {
          // Before `result` settles, so a caller that awaited it can ask for the same delete again.
          pending.delete(key);
        }
      });
      pending.set(key, result);
      tail = result.catch(() => undefined);
      return result;
    },
  };
}
