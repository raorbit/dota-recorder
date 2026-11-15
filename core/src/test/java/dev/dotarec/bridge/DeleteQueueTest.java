package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.dotarec.bridge.DeleteQueue.Kind;
import dev.dotarec.retention.StorageMaintenanceLock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DeleteQueue}: a delete waits a bounded time for the storage-maintenance lock on its request
 * thread and runs there if it gets it, else it is queued to run on the worker once the lock frees. A
 * storage pass is a {@link HeldLock} on another thread. The wait is cut to {@link #WAIT} so a queued
 * delete doesn't take half a minute to see.
 */
class DeleteQueueTest {

    private static final Duration WAIT = Duration.ofMillis(150);
    /** How long a test waits for the worker to get through what it was given. */
    private static final Duration WORKER = Duration.ofSeconds(5);

    private final StorageMaintenanceLock lock = new StorageMaintenanceLock();
    private final EventPublisher events = mock(EventPublisher.class);
    private final DeleteQueue queue = new DeleteQueue(lock, events, WAIT);
    /** What ran, in order: "ran X" for a delete, "announced X" for its frame. */
    private final List<String> log = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopWorker() {
        queue.shutdown();
    }

    @Test
    void runsADeleteOnTheCallingThread_whenTheLockIsFree() {
        Thread caller = Thread.currentThread();
        List<Thread> ranOn = new CopyOnWriteArrayList<>();
        List<Boolean> lockFreeWhileRunning = new CopyOnWriteArrayList<>();

        Optional<String> outcome = queue.run(Kind.MATCH, 7, () -> {
            ranOn.add(Thread.currentThread());
            lockFreeWhileRunning.add(HeldLock.isFree(lock));
            log.add("ran 7");
            return "deleted";
        }, done -> log.add("announced 7 " + done));

        assertThat(outcome).contains("deleted");
        assertThat(ranOn).containsExactly(caller);
        assertThat(lockFreeWhileRunning).as("it runs with the lock held").containsExactly(false);
        assertThat(log).containsExactly("ran 7", "announced 7 deleted");
        assertThat(queue.pendingIds(Kind.MATCH)).isEmpty();
        assertThat(HeldLock.isFree(lock)).as("and lets it go").isTrue();
        // Nothing was queued, so there is no pending change to broadcast.
        verify(events, never()).publishStatus();
    }

    @Test
    void queuesADeleteThatCantGetTheLockWithinTheWait_andRunsItOnceTheLockFrees() throws Exception {
        // The backstop ends the pass after 3 s, so a delete that waited for the lock itself fails on
        // the time it took instead of hanging the test.
        try (HeldLock pass = HeldLock.hold(lock).releaseAfter(Duration.ofSeconds(3))) {
            long start = System.nanoTime();
            Optional<String> outcome = queue.run(Kind.MATCH, 7, delete("7"), announce("7"));
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(outcome).as("queued, not run").isEmpty();
            assertThat(waitedMs).as("after waiting out the bound, and no longer")
                    .isGreaterThanOrEqualTo(WAIT.toMillis() - 10)
                    .isLessThan(2_000);
            assertThat(log).isEmpty();
            assertThat(queue.pendingIds(Kind.MATCH)).containsExactly(7L);
            verify(events).publishStatus();

            pass.release();
            queue.drain(WORKER);
        }
        assertThat(log).containsExactly("ran 7", "announced 7 deleted");
        assertThat(queue.pendingIds(Kind.MATCH)).isEmpty();
        // Once when it was queued, once when it was done.
        verify(events, times(2)).publishStatus();
        assertThat(HeldLock.isFree(lock)).as("the worker let go of the lock").isTrue();
    }

    @Test
    void queuesADeleteAtOnce_whileAnotherOneIsPending() throws Exception {
        try (HeldLock pass = HeldLock.hold(lock)) {
            assertThat(queue.run(Kind.MATCH, 7, delete("7"), announce("7"))).isEmpty();

            // 7 waits for the lock already, so 8 could only wait behind it: no second wait.
            long start = System.nanoTime();
            assertThat(queue.run(Kind.MATCH, 8, delete("8"), announce("8"))).isEmpty();
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(waitedMs).isLessThan(WAIT.toMillis());
            assertThat(queue.pendingIds(Kind.MATCH)).containsExactly(7L, 8L);
            pass.release();
            queue.drain(WORKER);
        }
        assertThat(log).containsExactly("ran 7", "announced 7 deleted", "ran 8", "announced 8 deleted");
        assertThat(queue.pendingIds(Kind.MATCH)).isEmpty();
    }

    @Test
    void answersASecondDeleteOfAPendingOne_withoutRunningItAgain() throws Exception {
        try (HeldLock pass = HeldLock.hold(lock)) {
            assertThat(queue.run(Kind.MATCH, 7, delete("7"), announce("7"))).isEmpty();
            assertThat(queue.run(Kind.MATCH, 7, delete("7 again"), announce("7 again"))).isEmpty();

            assertThat(queue.pendingIds(Kind.MATCH)).containsExactly(7L);
            pass.release();
            queue.drain(WORKER);
        }
        assertThat(log).containsExactly("ran 7", "announced 7 deleted");
        verify(events, times(2)).publishStatus();
    }

    @Test
    void keepsARecordingAndAClipWithTheSameIdApart() throws Exception {
        // Matches and clips are separate tables, so their ids overlap: recording 7 and clip 7 are two
        // different deletes, and neither may stand in for the other.
        try (HeldLock pass = HeldLock.hold(lock)) {
            assertThat(queue.run(Kind.MATCH, 7, delete("match 7"), announce("match 7"))).isEmpty();
            assertThat(queue.run(Kind.CLIP, 7, delete("clip 7"), announce("clip 7"))).isEmpty();

            assertThat(queue.pendingIds(Kind.MATCH)).containsExactly(7L);
            assertThat(queue.pendingIds(Kind.CLIP)).containsExactly(7L);
            pass.release();
            queue.drain(WORKER);
        }
        assertThat(log).containsExactly(
                "ran match 7", "announced match 7 deleted", "ran clip 7", "announced clip 7 deleted");
    }

    @Test
    void announcesADeleteOnlyOnceItIsNoLongerPending() throws Exception {
        // The frame tells every view the delete is done, so a status built after it must not still list
        // it: the row would be marked as being deleted again.
        List<List<Long>> pendingWhenAnnounced = new CopyOnWriteArrayList<>();
        queue.run(Kind.MATCH, 7, () -> "deleted",
                done -> pendingWhenAnnounced.add(queue.pendingIds(Kind.MATCH)));
        try (HeldLock pass = HeldLock.hold(lock)) {
            queue.run(Kind.MATCH, 8, () -> "deleted",
                    done -> pendingWhenAnnounced.add(queue.pendingIds(Kind.MATCH)));
            pass.release();
            queue.drain(WORKER);
        }

        assertThat(pendingWhenAnnounced).containsExactly(List.of(), List.of());
    }

    @Test
    void aDeleteThatFailsOnTheCallingThread_throws_andIsNoLongerPending() {
        assertThatThrownBy(() -> queue.run(Kind.MATCH, 7, () -> {
            throw new IllegalStateException("database is locked");
        }, done -> log.add("announced 7"))).hasMessage("database is locked");

        assertThat(log).as("nothing is announced").isEmpty();
        assertThat(queue.pendingIds(Kind.MATCH)).isEmpty();
        assertThat(HeldLock.isFree(lock)).isTrue();
    }

    @Test
    void aQueuedDeleteThatFails_isNoLongerPending() throws Exception {
        try (HeldLock pass = HeldLock.hold(lock)) {
            queue.run(Kind.CLIP, 5, () -> {
                throw new IllegalStateException("database is locked");
            }, done -> log.add("announced 5"));
            pass.release();
            queue.drain(WORKER);
        }

        verify(events, never()).publish(eq("clip.deleted"), any());
        verify(events, times(2)).publishStatus();
        assertThat(log).isEmpty();
        assertThat(queue.pendingIds(Kind.CLIP)).isEmpty();
        assertThat(HeldLock.isFree(lock)).as("the worker let go of the lock").isTrue();
    }

    @Test
    void aDeleteStillQueuedWhenTheCoreShutsDown_isDropped() throws Exception {
        try (HeldLock pass = HeldLock.hold(lock)) {
            queue.run(Kind.MATCH, 7, delete("7"), announce("7"));
            queue.shutdown();
            awaitNoPending();
        }

        assertThat(log).as("it never ran").isEmpty();
        assertThat(HeldLock.isFree(lock)).isTrue();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Supplier<String> delete(String what) {
        return () -> {
            log.add("ran " + what);
            return "deleted";
        };
    }

    private Consumer<String> announce(String what) {
        return done -> log.add("announced " + what + " " + done);
    }

    /** Polls until nothing is pending: a shut-down worker can't be drained. */
    private void awaitNoPending() throws InterruptedException {
        long deadline = System.nanoTime() + WORKER.toNanos();
        while (!queue.pendingIds(Kind.MATCH).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(queue.pendingIds(Kind.MATCH)).isEmpty();
    }
}
