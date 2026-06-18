package dev.dotarec.obs;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles OBS {@code RecordStateChanged} events and exposes the confirmed recording facts the FSM
 * needs to anchor markers.
 *
 * <p>obs-websocket v5 emits {@code RecordStateChanged} with an {@code outputState} string that
 * transitions {@code STARTING -> STARTED -> STOPPING -> STOPPED} (plus RECONNECTING/PAUSED/etc.).
 * Only {@code OBS_WEBSOCKET_OUTPUT_STARTED} means frames are actually being written, so that is the
 * single instant we treat recording as "real" and capture as the {@code recordConfirmedWallMs}
 * anchor (see {@code RecordingSession}). The {@code STARTED} event's {@code outputPath} is
 * {@code null} in v5 (a known obs-websocket regression), so the output path is captured from the
 * {@code STOPPED} event here as a <em>fallback</em>; the primary path source is the synchronous
 * {@code StopRecord} response read by {@link ObsController#stopRecording()}.
 *
 * <p>Threading: {@link #onRecordStateChanged} runs on the obs-websocket library's socket thread.
 * It must never block (no I/O, no waiting) -- it only flips the {@code volatile} health flags and
 * stores two small values, then returns. Heavier work (thumbnailing, persistence) is the FSM's job
 * on its own thread.
 */
@Component
public class ObsEvents {

    private static final Logger log = LoggerFactory.getLogger(ObsEvents.class);

    /** obs-websocket v5 outputState constants we react to. */
    static final String OUTPUT_STARTED = "OBS_WEBSOCKET_OUTPUT_STARTED";
    static final String OUTPUT_STOPPED = "OBS_WEBSOCKET_OUTPUT_STOPPED";

    private final ObsHealth health;

    /** Wall-clock instant of the most recent confirmed OUTPUT_STARTED; null until first start. */
    private final AtomicReference<Instant> recordConfirmedAt = new AtomicReference<>();

    /** Output path captured from the most recent STOPPED event (fallback path source). */
    private final AtomicReference<String> lastStoppedOutputPath = new AtomicReference<>();

    public ObsEvents(ObsHealth health) {
        this.health = health;
    }

    /**
     * Callback for {@code RecordStateChangedEvent}. Invoked on the library socket thread; keep it
     * fast and non-blocking.
     *
     * @param outputState the v5 {@code outputState} string (e.g. {@code OBS_WEBSOCKET_OUTPUT_STARTED})
     * @param outputPath the event's outputPath (null on STARTED in v5; populated on STOPPED)
     */
    public void onRecordStateChanged(String outputState, String outputPath) {
        if (outputState == null) {
            return;
        }
        switch (outputState) {
            case OUTPUT_STARTED -> {
                // Capture the confirmed instant FIRST, then publish recording=true so a reader that
                // sees recording can always read back a non-null start instant.
                recordConfirmedAt.set(Instant.now());
                health.setRecording(true);
                log.info("OBS recording confirmed started (OUTPUT_STARTED)");
            }
            case OUTPUT_STOPPED -> {
                if (outputPath != null && !outputPath.isBlank()) {
                    lastStoppedOutputPath.set(outputPath);
                }
                health.setRecording(false);
                log.info("OBS recording stopped; event outputPath={}", outputPath);
            }
            default -> {
                // STARTING / STOPPING / RECONNECTING / PAUSED / RESUMED: nothing to persist; the
                // STARTED/STOPPED transitions are the only load-bearing states for this app.
                log.debug("OBS record state: {}", outputState);
            }
        }
    }

    /**
     * The wall-clock instant OBS confirmed {@code OUTPUT_STARTED} for the current/most-recent
     * recording, or {@code null} if recording has never been confirmed since boot. The FSM reads
     * this immediately after {@link ObsController#startRecording()} returns to populate
     * {@code RecordingSession.recordConfirmedWallMs}.
     */
    public Instant recordConfirmedAt() {
        return recordConfirmedAt.get();
    }

    /**
     * Output path seen on the last STOPPED event, or {@code null}. This is a fallback only -- the
     * primary path comes from the synchronous StopRecord response.
     */
    public String lastStoppedOutputPath() {
        return lastStoppedOutputPath.get();
    }

    /** Clears the per-recording state so a fresh recording cannot read a stale start instant/path. */
    public void reset() {
        recordConfirmedAt.set(null);
        lastStoppedOutputPath.set(null);
    }
}
