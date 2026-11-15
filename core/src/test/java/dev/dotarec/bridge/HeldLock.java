package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dotarec.retention.StorageMaintenanceLock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A storage pass, for the delete tests: holds a maintenance lock on its own thread, as the archiver
 * does through its cross-drive copies, until it is released. Closing it releases it too.
 */
final class HeldLock implements AutoCloseable {

    private final CountDownLatch release = new CountDownLatch(1);
    private final Thread pass;

    private HeldLock(StorageMaintenanceLock lock) throws InterruptedException {
        CountDownLatch held = new CountDownLatch(1);
        pass = new Thread(() -> {
            lock.lock();
            try {
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "held-maintenance-lock");
        pass.setDaemon(true);
        pass.start();
        assertThat(held.await(5, TimeUnit.SECONDS)).as("the pass took the lock").isTrue();
    }

    /**
     * Takes {@code lock} on a new thread and returns once that thread holds it. The pass ends by
     * itself after ten seconds at the latest (see {@link #releaseAfter}), so a test whose delete waited
     * for the lock fails instead of hanging.
     */
    static HeldLock hold(StorageMaintenanceLock lock) throws InterruptedException {
        return new HeldLock(lock).releaseAfter(Duration.ofSeconds(10));
    }

    /**
     * Whether another thread can take {@code lock} right now (it lets go of it at once). A thread that
     * holds the lock always gets it again, so only another thread can tell whether it is held.
     */
    static boolean isFree(StorageMaintenanceLock lock) {
        boolean[] taken = new boolean[1];
        Thread other = new Thread(() -> {
            try {
                if (lock.tryLock(Duration.ofMillis(50))) {
                    taken[0] = true;
                    lock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        other.start();
        try {
            other.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while probing the lock", e);
        }
        assertThat(other.isAlive()).isFalse();
        return taken[0];
    }

    /** Ends the pass: the lock is free once this returns. */
    void release() throws InterruptedException {
        release.countDown();
        pass.join(5_000);
        assertThat(pass.isAlive()).as("the pass let go of the lock").isFalse();
    }

    /**
     * Ends the pass after {@code delay} unless the test has by then. A backstop for a test about a
     * wait that should give up: if it waited for the lock instead, it fails on the time it took or
     * what it found rather than hanging.
     */
    HeldLock releaseAfter(Duration delay) {
        Thread backstop = new Thread(() -> {
            try {
                if (!release.await(delay.toMillis(), TimeUnit.MILLISECONDS)) {
                    release.countDown();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "held-maintenance-lock-backstop");
        backstop.setDaemon(true);
        backstop.start();
        return this;
    }

    @Override
    public void close() throws InterruptedException {
        release();
    }
}
