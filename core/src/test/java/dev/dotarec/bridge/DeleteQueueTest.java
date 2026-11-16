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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
    /** When the row each delete is for was created, unless a test says otherwise. */
    private static final long CREATED_AT = 1_000L;

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

        Optional<String> outcome = queue.run(Kind.MATCH, 7, CREATED_AT, () -> {
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
            Optional<String> outcome =
                    queue.run(Kind.MATCH, 7, CREATED_AT, delete("7"), announce("7"));
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
            assertThat(queue.run(Kind.MATCH, 7, CREATED_AT, delete("7"), announce("7"))).isEmpty();

            // 7 waits for the lock already, so 8 could only wait behind it: no second wait.
            long start = System.nanoTime();
            assertThat(queue.run(Kind.MATCH, 8, CREATED_AT, delete("8"), announce("8"))).isEmpty();
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
    void runsADeleteThatWaitedOutItsTime_beforeOneQueuedDuringThatWait() throws Exception {
        // Clip 5 is deleted, then its recording 9 while 5 still waits for the lock. The pass outlasts
        // 5's wait. 5 must still run first: run after 9, it would leave 9 a stub for a clip about to go.
        DeleteQueue slow = new DeleteQueue(lock, events, Duration.ofSeconds(1));
        AtomicReference<Optional<String>> clipOutcome = new AtomicReference<>();
        try (HeldLock pass = HeldLock.hold(lock)) {
            Thread clipRequest = runOnItsOwnThread(slow, Kind.CLIP, 5, "clip 5", clipOutcome);
            awaitPending(slow, Kind.CLIP, 5);
            assertThat(slow.run(
                    Kind.MATCH, 9, CREATED_AT, delete("match 9"), announce("match 9"))).isEmpty();
            clipRequest.join(WORKER.toMillis());
            assertThat(clipOutcome.get()).as("5 waited out its time, so it was queued").isEmpty();

            pass.release();
            slow.drain(WORKER);
        } finally {
            slow.shutdown();
        }
        assertThat(ran()).containsExactly("ran clip 5", "ran match 9");
    }

    @Test
    void runsADeleteThatGetsTheLockDuringItsWait_beforeOneQueuedDuringThatWait() throws Exception {
        // As above, but the pass ends while 5 still waits: 5 runs on its own request, then 9.
        DeleteQueue slow = new DeleteQueue(lock, events, Duration.ofSeconds(5));
        AtomicReference<Optional<String>> clipOutcome = new AtomicReference<>();
        try (HeldLock pass = HeldLock.hold(lock)) {
            Thread clipRequest = runOnItsOwnThread(slow, Kind.CLIP, 5, "clip 5", clipOutcome);
            awaitPending(slow, Kind.CLIP, 5);
            assertThat(slow.run(
                    Kind.MATCH, 9, CREATED_AT, delete("match 9"), announce("match 9"))).isEmpty();

            pass.release();
            clipRequest.join(WORKER.toMillis());
            slow.drain(WORKER);
        } finally {
            slow.shutdown();
        }
        assertThat(clipOutcome.get()).contains("deleted");
        assertThat(ran()).containsExactly("ran clip 5", "ran match 9");
    }

    @Test
    void answersASecondDeleteOfAPendingOne_withoutRunningItAgain() throws Exception {
        try (HeldLock pass = HeldLock.hold(lock)) {
            assertThat(queue.run(Kind.MATCH, 7, CREATED_AT, delete("7"), announce("7"))).isEmpty();
            assertThat(queue.run(
                    Kind.MATCH, 7, CREATED_AT, delete("7 again"), announce("7 again"))).isEmpty();

            assertThat(queue.pendingIds(Kind.MATCH)).containsExactly(7L);
            pass.release();
            queue.drain(WORKER);
        }
        assertThat(log).containsExactly("ran 7", "announced 7 deleted");
        verify(events, times(2)).publishStatus();
    }

    @Test
    void runsADeleteOfANewerRowThatTookAPendingOnesId_asADeleteOfItsOwn() throws Exception {
        // Recording 7 is removed while its delete waits (boot recovery releasing it, say), and SQLite
        // gives its id to the next recording stored. A delete of that one isn't the first one asked for
        // again: both run, oldest first, and the status lists the id once.
        try (HeldLock pass = HeldLock.hold(lock)) {
            assertThat(queue.run(Kind.MATCH, 7, CREATED_AT, delete("7"), announce("7"))).isEmpty();
            assertThat(queue.run(
                    Kind.MATCH, 7, CREATED_AT + 1, delete("newer 7"), announce("newer 7"))).isEmpty();

            assertThat(queue.pendingIds(Kind.MATCH)).containsExactly(7L);
            pass.release();
            queue.drain(WORKER);
        }
        assertThat(log).containsExactly(
                "ran 7", "announced 7 deleted", "ran newer 7", "announced newer 7 deleted");
        assertThat(queue.pendingIds(Kind.MATCH)).isEmpty();
    }

    @Test
    void keepsARecordingAndAClipWithTheSameIdApart() throws Exception {
        // Matches and clips are separate tables, so their ids overlap: recording 7 and clip 7 are two
        // different deletes, and neither may stand in for the other.
        try (HeldLock pass = HeldLock.hold(lock)) {
            assertThat(queue.run(
                    Kind.MATCH, 7, CREATED_AT, delete("match 7"), announce("match 7"))).isEmpty();
            assertThat(queue.run(
                    Kind.CLIP, 7, CREATED_AT, delete("clip 7"), announce("clip 7"))).isEmpty();

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
        queue.run(Kind.MATCH, 7, CREATED_AT, () -> "deleted",
                done -> pendingWhenAnnounced.add(queue.pendingIds(Kind.MATCH)));
        try (HeldLock pass = HeldLock.hold(lock)) {
            queue.run(Kind.MATCH, 8, CREATED_AT, () -> "deleted",
                    done -> pendingWhenAnnounced.add(queue.pendingIds(Kind.MATCH)));
            pass.release();
            queue.drain(WORKER);
        }

        assertThat(pendingWhenAnnounced).containsExactly(List.of(), List.of());
    }

    @Test
    void aDeleteThatFailsOnTheCallingThread_throws_andIsNoLongerPending() {
        assertThatThrownBy(() -> queue.run(Kind.MATCH, 7, CREATED_AT, () -> {
            throw new IllegalStateException("database is locked");
        }, done -> log.add("announced 7"))).hasMessage("database is locked");

        assertThat(log).as("nothing is announced").isEmpty();
        assertThat(queue.pendingIds(Kind.MATCH)).isEmpty();
        assertThat(HeldLock.isFree(lock)).isTrue();
    }

    @Test
    void aQueuedDeleteThatFails_saysSoInADeleteFailedFrame() throws Exception {
        try (HeldLock pass = HeldLock.hold(lock)) {
            queue.run(Kind.CLIP, 5, CREATED_AT, () -> {
                throw new IllegalStateException("database is locked");
            }, done -> log.add("announced 5"));
            pass.release();
            queue.drain(WORKER);
        }

        // Kept for the next window when none is open to take it now.
        verify(events).publishOrKeep("delete.failed",
                Map.of("kind", "clip", "id", 5L, "message", "database is locked"));
        verify(events, never()).publish(eq("clip.deleted"), any());
        verify(events, times(2)).publishStatus();
        assertThat(log).isEmpty();
        assertThat(queue.pendingIds(Kind.CLIP)).isEmpty();
        assertThat(HeldLock.isFree(lock)).as("the worker let go of the lock").isTrue();
    }

    @Test
    void aDeleteStillQueuedWhenTheCoreShutsDown_isDropped() throws Exception {
        try (HeldLock pass = HeldLock.hold(lock)) {
            queue.run(Kind.MATCH, 7, CREATED_AT, delete("7"), announce("7"));
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

    /** The deletes that ran, in order, without their frames. */
    private List<String> ran() {
        return log.stream().filter(entry -> entry.startsWith("ran ")).toList();
    }

    /** Asks {@code on} for a delete on a request thread of its own, which sets {@code outcome}. */
    private Thread runOnItsOwnThread(DeleteQueue on, Kind kind, long id, String what,
                                     AtomicReference<Optional<String>> outcome) {
        Thread request = new Thread(
                () -> outcome.set(on.run(kind, id, CREATED_AT, delete(what), announce(what))),
                "delete-request");
        request.setDaemon(true);
        request.start();
        return request;
    }

    /** Polls until {@code on} has the delete pending, so a later one is asked for while it waits. */
    private static void awaitPending(DeleteQueue on, Kind kind, long id) throws InterruptedException {
        long deadline = System.nanoTime() + WORKER.toNanos();
        while (!on.pendingIds(kind).contains(id) && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(on.pendingIds(kind)).contains(id);
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
