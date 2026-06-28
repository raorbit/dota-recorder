package dev.dotarec.fsm;

import dev.dotarec.gsi.GsiHeartbeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Watchdog that force-finalizes an in-flight recording when the GSI feed goes silent mid-match.
 *
 * <p>Without this, a Dota crash / GSI drop while RECORDING would never deliver the POST_GAME frame
 * that normally cuts the recording, so OBS would record forever and no {@code matches} row would be
 * written. Every 5s this checks: are we RECORDING, and has it been &gt;= {@link #SILENCE_THRESHOLD_MS}
 * since the last frame? If so it fires {@link MatchFsm#forceFinalize()} (idempotent -- a no-op once
 * the state has moved off RECORDING, e.g. a normal POST_GAME beat us to it).
 *
 * <p>The silence threshold ({@value #SILENCE_THRESHOLD_MS}ms) is deliberately much larger than
 * {@code GsiHeartbeat}'s 5s ALIVE_WINDOW: that window drives the UI's green/red dot and should flip
 * the instant frames stop, whereas cutting a recording must tolerate brief drops (reconnects,
 * abandons, a momentary hitch) without losing the match. So the watchdog reads
 * {@link GsiHeartbeat#lastAuthorizedFrameAgoMillis()} directly rather than
 * {@link GsiHeartbeat#isAlive()}, which is hardwired to the 5s UI window. It reads the AUTHORIZED
 * timestamp (not the raw heartbeat) so malformed/unauthenticated POSTs to the token-exempt /gsi
 * endpoint cannot keep a runaway recording alive past a real GSI silence.
 *
 * <p>Threading: this runs on the Spring scheduler thread while {@link MatchFsm#onFrame} runs on the
 * GSI request thread. Both {@code onFrame} and {@code forceFinalize} are {@code synchronized} on the
 * same {@link MatchFsm} instance and flip RECORDING-&gt;STOPPING before any side effect, so the race
 * with a concurrent normal finalize resolves to exactly one OBS stop and one persisted row.
 */
@Component
public class ForceStopWatchdog {

    private static final Logger log = LoggerFactory.getLogger(ForceStopWatchdog.class);

    /**
     * No frame for this long while RECORDING means the feed is gone for good; cut the recording.
     * Much larger than the heartbeat's 5s liveness window so brief GSI drops don't end a match.
     */
    static final long SILENCE_THRESHOLD_MS = 30_000L;

    private final MatchFsm fsm;
    private final GsiHeartbeat heartbeat;

    public ForceStopWatchdog(MatchFsm fsm, GsiHeartbeat heartbeat) {
        this.fsm = fsm;
        this.heartbeat = heartbeat;
    }

    @Scheduled(fixedDelay = 5_000)
    public void tick() {
        if (fsm.getState() != MatchState.RECORDING) {
            return;
        }
        long ago = heartbeat.lastAuthorizedFrameAgoMillis();
        if (ago >= SILENCE_THRESHOLD_MS) {
            log.warn("GSI silent for {}ms while recording; force-finalizing", ago);
            fsm.forceFinalize();
        }
    }
}
