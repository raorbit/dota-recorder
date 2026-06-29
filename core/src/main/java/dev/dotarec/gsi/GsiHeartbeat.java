package dev.dotarec.gsi;

import org.springframework.stereotype.Component;

/**
 * Tracks when the last GSI frame arrived so the UI can show GSI "alive/dead" health.
 *
 * <p>Plan: the status card is green "when receiving frames". GSI cadence is ~10Hz (see
 * {@code GsiCfgInstaller} throttle), so a multi-second gap means the feed dropped.
 *
 * <p>TODO(plan: Edge cases): a grace window distinct from this raw liveness check should keep
 * recording through brief GSI drops (abandons / reconnects).
 */
@Component
public class GsiHeartbeat {

    /** Liveness threshold: no frame within this window means GSI is considered dead. */
    private static final long ALIVE_WINDOW_MILLIS = 5_000L;

    private volatile long lastFrameEpochMillis = 0L;
    // The authorized-frame liveness instant the watchdog reads is a MONOTONIC (System.nanoTime)
    // stamp, not wall-clock: a forward OS/NTP clock step must never make a healthy in-progress
    // recording look silent for >=30s and trip force-finalization. 0 means "no authorized frame yet"
    // (nanoTime can legitimately be 0/negative, so a separate flag tracks the unset state).
    private volatile boolean authorizedFrameSeen = false;
    private volatile long lastAuthorizedFrameNanos = 0L;

    /**
     * Marks raw connectivity: ANY POST to the GSI endpoint, including an empty keep-alive ping or a
     * frame that later fails to parse/authenticate. Drives the UI's green/red dot only -- the
     * watchdog deliberately ignores it (see {@link #markAuthorized()}).
     */
    public void mark() {
        lastFrameEpochMillis = System.currentTimeMillis();
    }

    /**
     * Marks an AUTHORIZED frame -- one that passed the GSI token check and will drive the FSM. The
     * {@code ForceStopWatchdog} reads this timestamp (not the raw heartbeat) so a stream of malformed
     * or wrong-token POSTs -- which a browser/local process can send to the token-exempt /gsi endpoint
     * -- can never keep a runaway recording alive past a genuine GSI silence.
     */
    public void markAuthorized() {
        lastAuthorizedFrameNanos = System.nanoTime();
        authorizedFrameSeen = true;
    }

    /**
     * Test/internal hook: stamps the raw last-frame instant at an arbitrary epoch so a test can
     * simulate a stale feed (e.g. {@code now - 31s}) without sleeping. Production code only ever calls
     * {@link #mark()}; this is {@code public} only so cross-package tests can reach it.
     */
    public void markAt(long epochMillis) {
        lastFrameEpochMillis = epochMillis;
    }

    /**
     * Test/internal hook: stamps the authorized-frame instant at an arbitrary {@code System.nanoTime()}
     * value so a test can simulate a stale feed (e.g. {@code nanoTime() - 31s}) without sleeping.
     * Production code only ever calls {@link #markAuthorized()}.
     */
    public void markAuthorizedAtNanos(long nanos) {
        lastAuthorizedFrameNanos = nanos;
        authorizedFrameSeen = true;
    }

    public long lastFrameAgoMillis() {
        long last = lastFrameEpochMillis;
        return last == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - last;
    }

    /**
     * Millis since the last AUTHORIZED frame (Long.MAX_VALUE if none yet), derived from a MONOTONIC
     * {@code System.nanoTime()} delta so a wall-clock step can't fabricate a silence. The watchdog
     * reads this.
     */
    public long lastAuthorizedFrameAgoMillis() {
        if (!authorizedFrameSeen) {
            return Long.MAX_VALUE;
        }
        return (System.nanoTime() - lastAuthorizedFrameNanos) / 1_000_000L;
    }

    public boolean isAlive() {
        return lastFrameAgoMillis() <= ALIVE_WINDOW_MILLIS;
    }
}
