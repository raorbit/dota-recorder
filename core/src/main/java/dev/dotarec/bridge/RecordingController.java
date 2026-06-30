package dev.dotarec.bridge;

import dev.dotarec.fsm.MatchFsm;
import dev.dotarec.fsm.MatchState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual recording control consumed by the Electron UI over the loopback bridge.
 *
 * <p>Contract: {@code POST /recording/stop} -> 200 with {@link StopResult}. Force-finalizes the
 * in-flight recording exactly as the GSI-silence watchdog would (capture thumbnail BEFORE stop,
 * StopRecord, persist the {@code matches} row + buffered markers/pauses, publish
 * {@code match.recorded}), then resets the FSM to IDLE.
 *
 * <p>Why this exists: {@link dev.dotarec.fsm.ForceStopWatchdog} only finalizes after
 * {@code SILENCE_THRESHOLD_MS} of GSI silence. A bot / custom / abandoned match that never emits
 * {@code POST_GAME} while Dota keeps POSTing menu/post-game frames therefore leaves the FSM stuck in
 * RECORDING forever (the feed never goes silent, so the watchdog never trips). This endpoint is the
 * user's escape hatch for exactly that case -- it cuts the recording on demand and saves the match.
 *
 * <p>{@link MatchFsm#forceFinalize()} is {@code synchronized} and idempotent: a no-op when not
 * RECORDING, and it can never interleave with a concurrent GSI-frame finalize. {@code wasRecording}
 * is read just before the call (a best-effort flag) so the UI can distinguish "stopped your
 * recording" from "nothing was recording".
 */
@RestController
public class RecordingController {

    private static final Logger log = LoggerFactory.getLogger(RecordingController.class);

    private final MatchFsm fsm;
    private final EventPublisher events;

    public RecordingController(MatchFsm fsm, EventPublisher events) {
        this.fsm = fsm;
        this.events = events;
    }

    @PostMapping("/recording/stop")
    public StopResult stop() {
        boolean wasRecording = fsm.getState() == MatchState.RECORDING;
        if (wasRecording) {
            log.info("Manual stop requested; force-finalizing in-flight recording");
        }
        // forceFinalize is itself idempotent (a no-op off RECORDING), so the controller need not gate
        // it -- the wasRecording flag above is purely for the UI message.
        fsm.forceFinalize();
        // Push the new state immediately so the status card flips off "Recording" without waiting for
        // the 5s OBS-scheduler status heartbeat. Best-effort: a broadcast hiccup must not fail the stop.
        try {
            events.publishStatus();
        } catch (RuntimeException e) {
            log.debug("Status push after manual stop failed (best-effort): {}", e.toString());
        }
        return new StopResult(wasRecording);
    }

    /** {@code POST /recording/stop} response: whether a recording was in flight when the call landed. */
    public record StopResult(boolean wasRecording) {}
}
