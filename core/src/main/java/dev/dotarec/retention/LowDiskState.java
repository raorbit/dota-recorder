package dev.dotarec.retention;

import org.springframework.stereotype.Component;

/**
 * The latest low-disk observation, kept so {@code GET /status} (and the status push) can carry it.
 *
 * <p>{@link RetentionSweeper#checkFreeSpaceWarning()} runs hourly and used to publish a one-off
 * {@code error{scope:"disk"}} frame only; nothing retained it. That was fine while the renderer
 * lived for the whole session, but a window re-created from the tray would have shown no banner
 * for up to an hour, and the shell's own low-disk notification needs a readable value too. The
 * sweeper {@link #set sets} this when a check finds the drive low and {@link #clear clears} it when
 * a check finds it healthy; a failed probe leaves it as it was.
 *
 * <p>Separate from the sweeper so {@code StatusService} and {@code RetentionSweeper} both depend on
 * it rather than on each other. Volatile: written by the scheduler, read by request threads.
 */
@Component
public class LowDiskState {

    /**
     * One low-disk observation. {@code observedAtMs} is the wall-clock time of the check that saw
     * it: a consumer notifies on a NEW observation, never on every poll that still sees the same one.
     */
    public record LowDisk(long freeBytes, long thresholdBytes, String message, long observedAtMs) {}

    private volatile LowDisk current;

    /** Records the observation of the latest check that found the drive low. */
    public void set(LowDisk lowDisk) {
        current = lowDisk;
    }

    /** The latest check found the drive healthy. */
    public void clear() {
        current = null;
    }

    /** The current low-disk observation, or null while the drive is healthy (or never checked). */
    public LowDisk current() {
        return current;
    }
}
