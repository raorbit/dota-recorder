package dev.dotarec.bridge;

import dev.dotarec.retention.StorageMaintenanceLock;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Runs the library's recording and clip deletes ({@code DELETE /matches/{id}} and
 * {@code DELETE /clips/{clipId}}) under the {@link StorageMaintenanceLock} without keeping a request
 * waiting for as long as a storage pass holds it.
 *
 * <p>A delete must hold the lock (the two controllers say why), and the archiver holds it through a
 * whole pass of cross-drive copies: minutes for multi-GB VODs onto a slow drive, and for as long as a
 * hung copy hangs. A request that waited for it kept a bridge thread (the connector has 16) and one of
 * the renderer's connections to the core all that time. So a delete now waits at most
 * {@link #LOCK_WAIT} on its request thread. If it gets the lock by then, it runs there and the request
 * answers with what it did. If not, it goes to this component's one worker thread, which runs it once
 * the lock frees, and the request answers at once that it is queued. A delete that would only wait in
 * line behind another one (anything else pending) is queued without waiting, so at most one request
 * waits on the lock at a time.
 *
 * <p>Deletes run in the order they came, wherever they run: a recording deleted after its clips must
 * find them gone, or it is left a stub (see {@code MatchController}). While a request waits for the
 * lock, the worker leaves the lock alone, so a delete queued meanwhile can't take it first. One whose
 * wait runs out is then queued behind those later ones, but the worker always runs the oldest delete
 * it has.
 *
 * <p>Every delete the core has accepted and not finished is pending here, keyed by what it deletes (a
 * recording and a clip with the same number are two different deletes). The status payload lists them,
 * so every window shows those rows as being deleted, one opened after the delete was asked for
 * included. A second delete of a pending one runs nothing and is answered as queued: the first finishes
 * it. A delete's own frame ({@code match.deleted}, {@code clip.deleted}) goes out once it is done,
 * wherever it ran. A queued one that fails says so in a {@code delete.failed} frame, since no request is
 * left to carry the reason.
 *
 * <p>The queue lives only in memory. A delete still queued when the core exits (the app quits, or the
 * core crashes and is restarted) is lost, and its row is listed again as an ordinary one.
 */
@Component
public class DeleteQueue {

    private static final Logger log = LoggerFactory.getLogger(DeleteQueue.class);

    /** How long a delete waits for the maintenance lock on its request thread before it is queued. */
    static final Duration LOCK_WAIT = Duration.ofSeconds(30);

    /** What a pending delete deletes. */
    public enum Kind {
        MATCH("match"),
        CLIP("clip");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        /** Its name in the log and in a {@code delete.failed} frame. */
        String wire() {
            return wire;
        }
    }

    private record Key(Kind kind, long id) {}

    /** A queued delete, run by the worker once the lock is held ({@code locked}), or dropped. */
    @FunctionalInterface
    private interface QueuedDelete {
        void run(boolean locked);
    }

    private final StorageMaintenanceLock maintenanceLock;
    /** Publishes the status and failure frames; null in tests that don't observe them. */
    private final EventPublisher events;
    private final Duration lockWait;
    /** Every delete accepted and not finished, in the order they came. Guarded by itself. */
    private final Set<Key> pending = new LinkedHashSet<>();
    /** The queued deletes the worker hasn't taken yet. Guarded by {@link #pending}. */
    private final Map<Key, QueuedDelete> queued = new HashMap<>();
    /**
     * A request is waiting for the lock to run its delete itself, so the worker must not take the lock
     * first. Guarded by {@link #pending}, which is notified when it ends.
     */
    private boolean waitingOnRequest;
    /**
     * Runs the queued deletes. One thread, so they run one at a time, each time the oldest one queued
     * (see {@link #runNext()}). It exits once nothing has been queued for a minute, and is a daemon,
     * so a delete still waiting for the lock never holds up the core's exit.
     */
    private final ThreadPoolExecutor worker;

    // @Lazy breaks the construction cycle StatusService -> DeleteQueue -> EventPublisher ->
    // StatusService. The publisher is only used at runtime, once a delete is queued or done.
    @Autowired
    public DeleteQueue(StorageMaintenanceLock maintenanceLock, @Lazy EventPublisher events) {
        this(maintenanceLock, events, LOCK_WAIT);
    }

    /**
     * Test constructor: a wait other than {@link #LOCK_WAIT}, so a test needn't hold the lock for half
     * a minute to see a delete queued. Package-private so only the tests can build one.
     */
    DeleteQueue(StorageMaintenanceLock maintenanceLock, EventPublisher events, Duration lockWait) {
        this.maintenanceLock = maintenanceLock;
        this.events = events;
        this.lockWait = lockWait;
        this.worker = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "delete-queue");
                    t.setDaemon(true);
                    return t;
                });
        this.worker.allowCoreThreadTimeOut(true);
    }

    /**
     * Runs the delete of {@code kind} {@code id} now, or once the maintenance lock frees. {@code delete}
     * runs with the lock held and returns what it did; {@code announce} then gets that, with the lock
     * released, to publish the delete's frame. Returns what the delete did when it ran on the calling
     * thread (an exception it threw propagates, and nothing is announced). Returns empty when it was
     * queued instead, or when that same delete was already pending: it finishes without this call.
     */
    public <T> Optional<T> run(Kind kind, long id, Supplier<T> delete, Consumer<T> announce) {
        Key key = new Key(kind, id);
        boolean alone;
        synchronized (pending) {
            if (!pending.add(key)) {
                return Optional.empty();
            }
            // Anything else pending holds the lock or waits for it, so this one would only wait behind
            // it: queue it at once rather than keep a second request waiting on the same lock.
            alone = pending.size() == 1;
            if (alone) {
                waitingOnRequest = true;
            }
        }
        if (!alone) {
            queue(key, delete, announce, false);
            return Optional.empty();
        }
        if (!acquireWithin(lockWait)) {
            queue(key, delete, announce, true);
            return Optional.empty();
        }
        endRequestWait();
        T result;
        try {
            try {
                result = delete.get();
            } finally {
                maintenanceLock.unlock();
            }
        } finally {
            // Before the frame, so a status built after it never lists the delete as still pending.
            release(key);
        }
        announce.accept(result);
        return Optional.of(result);
    }

    /** The ids with a delete of {@code kind} pending, oldest first: the status payload lists them. */
    public List<Long> pendingIds(Kind kind) {
        synchronized (pending) {
            return pending.stream().filter(k -> k.kind() == kind).map(Key::id).toList();
        }
    }

    /** Stops the worker when the core shuts down. A delete still queued is dropped (see the class doc). */
    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    /**
     * Test hook: returns once every delete queued so far has run or been dropped. The worker takes them
     * in order, so a no-op handed to it after them runs last.
     */
    void drain(Duration timeout) throws Exception {
        worker.submit(() -> { }).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Hands the delete to the worker. {@code endsRequestWait} when it is the one a request waited for
     * the lock for: it is the worker's to run first, and the worker may take the lock from here on.
     */
    private <T> void queue(Key key, Supplier<T> delete, Consumer<T> announce, boolean endsRequestWait) {
        synchronized (pending) {
            // Before the wait ends, so the worker, once let go, finds this one queued.
            queued.put(key, locked -> runQueued(key, delete, announce, locked));
            if (endsRequestWait) {
                endRequestWait();
            }
        }
        try {
            worker.execute(this::runNext);
        } catch (RejectedExecutionException e) {
            // Only once the core is shutting down: nothing will run it, so it isn't pending.
            synchronized (pending) {
                queued.remove(key);
            }
            release(key);
            throw e;
        }
        log.info("Queued the delete of {} {}: the storage-maintenance lock is held", key.kind().wire(),
                key.id());
        publishStatus();
    }

    /**
     * One run of the worker: once no request waits for the lock and the worker has the lock, it runs
     * the oldest delete queued. Each queued delete hands the worker one run, so there is always one to
     * take. Interrupted ({@link #shutdown()}), it drops that delete instead.
     */
    private void runNext() {
        boolean locked = awaitRequestWait() && awaitLock();
        QueuedDelete next = null;
        synchronized (pending) {
            for (Key key : pending) {
                next = queued.remove(key);
                if (next != null) {
                    break;
                }
            }
        }
        if (next != null) {
            next.run(locked);
        } else if (locked) {
            maintenanceLock.unlock();
        }
    }

    /** A queued delete, on the worker: runs it if the worker got the lock, then announces what it did. */
    private <T> void runQueued(Key key, Supplier<T> delete, Consumer<T> announce, boolean locked) {
        T result = null;
        boolean ran = false;
        RuntimeException failure = null;
        try {
            if (locked) {
                try {
                    result = delete.get();
                    ran = true;
                } catch (RuntimeException e) {
                    failure = e;
                } finally {
                    maintenanceLock.unlock();
                }
            }
        } finally {
            // Before the frames, so a status built after them never lists the delete as still pending.
            release(key);
        }
        if (ran) {
            announce.accept(result);
        } else if (failure != null) {
            log.warn("The queued delete of {} {} failed", key.kind().wire(), key.id(), failure);
            publishFailure(key, failure);
        } else {
            log.info("Dropped the queued delete of {} {}: the core is shutting down", key.kind().wire(),
                    key.id());
        }
        publishStatus();
    }

    /** Waits up to {@code wait} for the lock; false when it stays held, or the wait is interrupted. */
    private boolean acquireWithin(Duration wait) {
        try {
            return maintenanceLock.tryLock(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The request that waited for the lock has it, or has queued its delete: the worker may go on. */
    private void endRequestWait() {
        synchronized (pending) {
            waitingOnRequest = false;
            pending.notifyAll();
        }
    }

    /**
     * Waits while a request waits for the lock to run its own delete, which came before any the worker
     * has. False when the worker is interrupted.
     */
    private boolean awaitRequestWait() {
        synchronized (pending) {
            try {
                while (waitingOnRequest) {
                    pending.wait();
                }
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Waits for the lock for as long as it is held. False only when the worker is interrupted, which
     * {@link #shutdown()} does; a bounded wait in a loop, so that interrupt always ends it.
     */
    private boolean awaitLock() {
        try {
            while (!maintenanceLock.tryLock(Duration.ofMinutes(1))) {
                // Still held by a storage pass: keep waiting.
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void release(Key key) {
        synchronized (pending) {
            pending.remove(key);
        }
    }

    /** Pushes a status frame, so every view sees the pending deletes change. Best-effort. */
    private void publishStatus() {
        if (events == null) {
            return;
        }
        try {
            events.publishStatus();
        } catch (RuntimeException e) {
            log.debug("Status broadcast after a delete-queue change failed: {}", e.toString());
        }
    }

    /** {@code delete.failed}: what a queued delete was, and why it failed. */
    private void publishFailure(Key key, RuntimeException failure) {
        if (events == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>(3);
        payload.put("kind", key.kind().wire());
        payload.put("id", key.id());
        String message = failure.getMessage();
        payload.put("message", message == null || message.isBlank() ? failure.toString() : message);
        events.publish("delete.failed", payload);
    }
}
