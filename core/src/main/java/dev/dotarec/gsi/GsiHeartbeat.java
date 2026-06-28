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
    private volatile long lastAuthorizedFrameEpochMillis = 0L;

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
        lastAuthorizedFrameEpochMillis = System.currentTimeMillis();
    }

    /**
     * Test/internal hook: stamps the raw last-frame instant at an arbitrary epoch so a test can
     * simulate a stale feed (e.g. {@code now - 31s}) without sleeping. Production code only ever calls
     * {@link #mark()}; this is {@code public} only so cross-package tests can reach it.
     */
    public void markAt(long epochMillis) {
        lastFrameEpochMillis = epochMillis;
    }

    /** Test/internal hook: stamps the authorized-frame instant; see {@link #markAt(long)}. */
    public void markAuthorizedAt(long epochMillis) {
        lastAuthorizedFrameEpochMillis = epochMillis;
    }

    public long lastFrameAgoMillis() {
        long last = lastFrameEpochMillis;
        return last == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - last;
    }

    /** Millis since the last AUTHORIZED frame (Long.MAX_VALUE if none yet). The watchdog reads this. */
    public long lastAuthorizedFrameAgoMillis() {
        long last = lastAuthorizedFrameEpochMillis;
        return last == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - last;
    }

    public boolean isAlive() {
        return lastFrameAgoMillis() <= ALIVE_WINDOW_MILLIS;
    }
}
