package dev.dotarec.retention;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * A single shared mutex serializing the two storage-maintenance passes that both mutate the same VOD
 * files and DB rows: the {@link RetentionSweeper}'s delete pass and the {@link RecordingArchiver}'s
 * move pass.
 *
 * <p>Why this exists: the scheduler pool has 4 threads, so the hourly {@code sweep()} and the 120s
 * {@code archive()} can fire on DIFFERENT pool threads and interleave on the same match — e.g. the
 * sweeper deletes a row's .mp4 just as the archiver is mid-copy of it, or repoints a row the sweeper
 * just nulled. Holding this lock around each pass's body makes the two mutually exclusive.
 *
 * <p>The lock is a {@link ReentrantLock} on purpose: {@code RecordingArchiver.archive(Long)} calls
 * {@code sweeper.sweep(protectedId)} directly on its OWN thread while already holding the lock, so the
 * nested acquisition must not self-deadlock. Reentrancy lets the same thread re-enter freely; a
 * non-reentrant mutex would hang the archiver on its own first pass.
 */
@Component
public class StorageMaintenanceLock {

    private final ReentrantLock lock = new ReentrantLock();

    /** Acquires the shared maintenance lock (reentrant on the calling thread). */
    public void lock() {
        lock.lock();
    }

    /** Releases one hold of the shared maintenance lock; pair with {@link #lock()} in a finally. */
    public void unlock() {
        lock.unlock();
    }
}
