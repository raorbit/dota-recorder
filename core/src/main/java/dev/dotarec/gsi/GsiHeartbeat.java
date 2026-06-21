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

    public void mark() {
        lastFrameEpochMillis = System.currentTimeMillis();
    }

    /**
     * Test/internal hook: stamps the last-frame instant at an arbitrary epoch so a test can simulate
     * a stale feed (e.g. {@code now - 31s}) without sleeping. Production code only ever calls
     * {@link #mark()}; this is {@code public} only so the watchdog test (a different package) can
     * reach it.
     */
    public void markAt(long epochMillis) {
        lastFrameEpochMillis = epochMillis;
    }

    public long lastFrameAgoMillis() {
        long last = lastFrameEpochMillis;
        return last == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - last;
    }

    public boolean isAlive() {
        return lastFrameAgoMillis() <= ALIVE_WINDOW_MILLIS;
    }
}
