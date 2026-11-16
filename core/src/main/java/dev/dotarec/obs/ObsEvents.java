package dev.dotarec.obs;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Handles OBS {@code RecordStateChanged} events and exposes the confirmed recording facts the FSM
 * needs to anchor markers.
 *
 * <p>obs-websocket v5 emits {@code RecordStateChanged} with an {@code outputState} string that
 * transitions {@code STARTING -> STARTED -> STOPPING -> STOPPED} (plus RECONNECTING/PAUSED/etc.).
 * Only {@code OBS_WEBSOCKET_OUTPUT_STARTED} means frames are actually being written, so that is the
 * single instant we treat recording as "real" and capture as the {@code recordConfirmedWallMs}
 * anchor (see {@code RecordingSession}). A stopped recording's path comes from the synchronous
 * {@code StopRecord} response read by {@link ObsController#stopRecording()}, with the {@code
 * STOPPED} event's path captured here as the <em>fallback</em>. The {@code STARTED} event names the
 * file too in the obs-websocket OBS ships now (it reads the recording output's path setting on both
 * STARTED and STOPPED), although its protocol docs still say STOPPED only and older versions sent
 * null; it is kept as the last resort for a recording whose OBS died without a STOPPED.
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

    /**
     * Arms the black-frame check on each confirmed start. {@code @Lazy} breaks the construction cycle
     * ObsEvents -> CaptureBlackFrameGuard -> ObsController -> ObsEvents; the guard is only dereferenced
     * at runtime from the OBS event thread, so a lazy proxy is safe and keeps the graph acyclic.
     */
    private final CaptureBlackFrameGuard blackFrameGuard;

    /** Wall-clock instant of the most recent confirmed OUTPUT_STARTED; null until first start. */
    private final AtomicReference<Instant> recordConfirmedAt = new AtomicReference<>();

    /** Output path captured from the most recent STOPPED event (fallback path source). */
    private final AtomicReference<String> lastStoppedOutputPath = new AtomicReference<>();

    /** Output path the most recent STARTED event reported, when it reported one. */
    private final AtomicReference<String> startedOutputPath = new AtomicReference<>();

    /**
     * Re-armable latch counted down on the next OUTPUT_STARTED. {@link #clearRecordConfirmation()}
     * installs a fresh one immediately before a StartRecord so {@link #awaitRecordConfirmed(long)}
     * blocks on THIS recording's confirmation, never a stale one — this is what stops a back-to-back
     * recording from anchoring on the previous match's confirmed instant. {@code volatile}: armed on
     * the FSM thread, counted down on the socket thread.
     */
    private volatile CountDownLatch confirmLatch;

    /**
     * Counted down on the OUTPUT_STOPPED that answers a StopRecord this app sent; null when no such
     * stop is outstanding. OBS answers StopRecord at once and closes the file about half a second
     * later, and until it has it refuses a StartRecord, so {@link #awaitOutputIdle(long, long)} is how
     * a back-to-back recording waits out that window. Outlives {@link #reset()}, which a start runs
     * before it gets as far as waiting. {@code volatile}: armed on the FSM or bridge thread, counted
     * down on the socket thread.
     */
    private volatile CountDownLatch stopLatch;

    /** {@code System.nanoTime()} at the latest OUTPUT_STOPPED; null until one has arrived. */
    private volatile Long stoppedAtNanos;

    public ObsEvents(ObsHealth health, @Lazy CaptureBlackFrameGuard blackFrameGuard) {
        this.health = health;
        this.blackFrameGuard = blackFrameGuard;
    }

    /**
     * Callback for {@code RecordStateChangedEvent}. Invoked on the library socket thread; keep it
     * fast and non-blocking.
     *
     * @param outputState the v5 {@code outputState} string (e.g. {@code OBS_WEBSOCKET_OUTPUT_STARTED})
     * @param outputPath the event's outputPath (populated on STARTED and STOPPED by the bundled
     *     obs-websocket; null on STARTED from older versions)
     */
    public void onRecordStateChanged(String outputState, String outputPath) {
        if (outputState == null) {
            return;
        }
        switch (outputState) {
            case OUTPUT_STARTED -> {
                // Capture the confirmed instant FIRST, then publish recording=true so a reader that
                // sees recording can always read back a non-null start instant, then release any
                // awaitRecordConfirmed() waiter LAST so it observes both.
                recordConfirmedAt.set(Instant.now());
                // A new output owns the STOPPED path from here on. reset() at the top of
                // ObsController.startRecording runs BEFORE its corrective StopRecord of the previous
                // recording, whose STOPPED would otherwise leave the old file's path looking like
                // this recording's. OBS stops the old output before it can start the new one, so
                // its STOPPED always lands before this STARTED.
                lastStoppedOutputPath.set(null);
                startedOutputPath.set(
                        outputPath != null && !outputPath.isBlank() ? outputPath : null);
                health.setRecording(true);
                CountDownLatch latch = confirmLatch;
                if (latch != null) {
                    latch.countDown();
                }
                log.info("OBS recording confirmed started (OUTPUT_STARTED)");
                // Schedule a delayed, off-thread check that the game capture isn't black. Non-blocking:
                // armCheck() only clears the prior flag and schedules; the sample runs on the guard's
                // own thread so this event callback stays fast.
                blackFrameGuard.armCheck();
            }
            case OUTPUT_STOPPED -> {
                if (outputPath != null && !outputPath.isBlank()) {
                    lastStoppedOutputPath.set(outputPath);
                }
                health.setRecording(false);
                // Stamp the instant before releasing the waiter, so it can measure its settle from it.
                stoppedAtNanos = System.nanoTime();
                CountDownLatch stopped = stopLatch;
                if (stopped != null) {
                    stopped.countDown();
                }
                // Deliberately does NOT clear captureBlack: during a black match the user is in
                // fullscreen Dota and only sees the app AFTER the recording ends, so a warning
                // that dies with the recording is never seen by the person it exists for. It
                // persists until the next recording arms (armCheck clears it).
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
     * Arms a fresh confirmation latch and clears the prior confirmed instant. Call IMMEDIATELY before
     * issuing StartRecord so {@link #awaitRecordConfirmed(long)} waits for the new recording's
     * OUTPUT_STARTED — without this a back-to-back recording could read the previous match's instant
     * (still non-null until the next reset) and anchor every marker to the wrong start.
     */
    public void clearRecordConfirmation() {
        recordConfirmedAt.set(null);
        confirmLatch = new CountDownLatch(1);
    }

    /**
     * Blocks up to {@code timeoutMs} for the OUTPUT_STARTED that confirms the in-flight recording and
     * returns its confirmed wall instant, or {@code null} if it does not arrive in time — a phantom /
     * black recording the caller should abort. If no latch is armed (no {@link #clearRecordConfirmation()}
     * preceded this), returns whatever instant is currently known without blocking.
     */
    public Instant awaitRecordConfirmed(long timeoutMs) {
        CountDownLatch latch = confirmLatch;
        if (latch == null) {
            return recordConfirmedAt.get();
        }
        try {
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return recordConfirmedAt.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /**
     * Arms the wait for the OUTPUT_STOPPED of a StopRecord. Call IMMEDIATELY before sending it, so an
     * event that beats the response is still counted.
     */
    public void expectStopped() {
        stopLatch = new CountDownLatch(1);
    }

    /**
     * Drops an armed wait whose OUTPUT_STOPPED is not coming: the StopRecord failed, the event already
     * landed before the wait was armed, or the connection it belonged to is gone. The settle after a
     * STOPPED that did arrive still applies.
     */
    public void cancelExpectedStop() {
        stopLatch = null;
    }

    /**
     * Blocks until OBS can take a StartRecord: up to {@code timeoutMs} for the OUTPUT_STOPPED of an
     * outstanding stop, then until {@code settleMs} have passed since the latest OUTPUT_STOPPED.
     * Returns at once when no stop is outstanding and none just landed.
     *
     * <p>The settle exists because the event alone is not enough. Live, a StartRecord that reached OBS
     * in the same instant as the STOPPED event was accepted and then never started, which cost a 15s
     * confirmation timeout and an OBS restart; starts sent a few tens of milliseconds after the event
     * all went through.
     *
     * @return false when the stop was not confirmed in time or the wait was interrupted
     */
    public boolean awaitOutputIdle(long timeoutMs, long settleMs) {
        CountDownLatch latch = stopLatch;
        try {
            if (latch != null && !latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return false;
            }
            Long stoppedAt = stoppedAtNanos;
            if (stoppedAt != null) {
                long remaining =
                        TimeUnit.MILLISECONDS.toNanos(settleMs) - (System.nanoTime() - stoppedAt);
                if (remaining > 0) {
                    TimeUnit.NANOSECONDS.sleep(remaining);
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Output path seen on a STOPPED event since the latest OUTPUT_STARTED, or {@code null}. This is
     * a fallback only -- the primary path comes from the synchronous StopRecord response -- and is
     * also what a finalize attaches when OBS ended the output on its own and StopRecord fails.
     */
    public String lastStoppedOutputPath() {
        return lastStoppedOutputPath.get();
    }

    /**
     * The output path the latest OUTPUT_STARTED reported, or {@code null} when it reported none (an
     * older obs-websocket sends none; see the class notes). The FSM keeps it with the recording, so a
     * recording whose OBS crashed, and so never reported a STOPPED path, can still keep its file.
     */
    public String startedOutputPath() {
        return startedOutputPath.get();
    }

    /**
     * Clears the per-recording state so a fresh recording cannot read a stale start instant/path.
     * Leaves the stop wait alone: a start runs this before it waits for the previous stop.
     */
    public void reset() {
        recordConfirmedAt.set(null);
        lastStoppedOutputPath.set(null);
        startedOutputPath.set(null);
        confirmLatch = null;
    }
}
