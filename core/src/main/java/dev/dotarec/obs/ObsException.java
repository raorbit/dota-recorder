package dev.dotarec.obs;

/**
 * Typed, recoverable error from OBS control: not connected, a request rejected/timed out, a
 * protocol mismatch, or a thumbnail failure.
 *
 * <p>Deliberately a {@link RuntimeException} so the {@link ObsRecorder} contract stays unchecked,
 * but a distinct type so callers (the FSM, the settings/arm flow) can catch <em>OBS</em> failures
 * specifically -- e.g. to surface a loud status-card error and refuse to arm, rather than dropping
 * a recording silently. It never represents a programming bug; it always represents an
 * operator/environment condition the UI should report.
 */
public class ObsException extends RuntimeException {

    public ObsException(String message) {
        super(message);
    }

    public ObsException(String message, Throwable cause) {
        super(message, cause);
    }
}
