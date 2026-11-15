package dev.dotarec.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * {@link StorageMaintenanceLock#tryLock(Duration)}: a bounded wait for the lock, for a caller that
 * must not wait out a whole storage pass. Contention comes from a second thread, since a thread that
 * already holds the lock always gets it again.
 */
class StorageMaintenanceLockTest {

    private final StorageMaintenanceLock lock = new StorageMaintenanceLock();

    @Test
    void tryLock_takesAFreeLock_andHoldsItUntilUnlocked() throws Exception {
        assertThat(lock.tryLock(Duration.ofSeconds(5))).isTrue();
        try {
            assertThat(takenBrieflyElsewhere()).as("another thread can't take it while it is held").isFalse();
        } finally {
            lock.unlock();
        }
        assertThat(takenBrieflyElsewhere()).as("once released it is free again").isTrue();
    }

    @Test
    void tryLock_givesUpOnceTheWaitIsOver_whileAPassHoldsTheLock() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread pass = holdElsewhereUntil(release);
        try {
            long start = System.nanoTime();
            boolean acquired = lock.tryLock(Duration.ofMillis(200));
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(acquired).isFalse();
            assertThat(waitedMs).as("it waits out the bound before giving up").isGreaterThanOrEqualTo(190);
            assertThat(waitedMs).as("and no longer than that").isLessThan(5_000);
        } finally {
            release.countDown();
            pass.join(5_000);
        }
    }

    @Test
    void tryLock_takesTheLockWhenThePassEndsWithinTheWait() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread pass = holdElsewhereUntil(release);
        Thread ender = new Thread(() -> {
            sleep(150);
            release.countDown();
        });
        ender.start();

        boolean acquired = lock.tryLock(Duration.ofSeconds(10));
        try {
            assertThat(acquired).isTrue();
        } finally {
            if (acquired) {
                lock.unlock();
            }
            release.countDown();
            pass.join(5_000);
            ender.join(5_000);
        }
    }

    @Test
    void tryLock_isReentrant_forTheThreadThatHoldsTheLock() throws Exception {
        lock.lock();
        try {
            assertThat(lock.tryLock(Duration.ZERO)).isTrue();
            lock.unlock();
        } finally {
            lock.unlock();
        }
        assertThat(takenBrieflyElsewhere()).as("both holds were released").isTrue();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Takes the lock on another thread, as a storage pass would, until {@code release} counts down. */
    private Thread holdElsewhereUntil(CountDownLatch release) throws InterruptedException {
        CountDownLatch held = new CountDownLatch(1);
        Thread pass = new Thread(() -> {
            lock.lock();
            try {
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        pass.start();
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        return pass;
    }

    /** Whether another thread gets the lock within a short bounded try (it releases what it takes). */
    private boolean takenBrieflyElsewhere() throws InterruptedException {
        AtomicBoolean taken = new AtomicBoolean();
        Thread other = new Thread(() -> {
            try {
                if (lock.tryLock(Duration.ofMillis(50))) {
                    taken.set(true);
                    lock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        other.start();
        other.join(5_000);
        assertThat(other.isAlive()).isFalse();
        return taken.get();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
