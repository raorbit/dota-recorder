package dev.dotarec.obs;

import io.obswebsocket.community.client.OBSRemoteController;
import io.obswebsocket.community.client.message.response.sources.SaveSourceScreenshotResponse;
import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.dotarec.bridge.EventPublisher;
import org.springframework.stereotype.Component;

/**
 * Detects a BLACK game capture shortly after a recording starts and surfaces it, so a silent black
 * VOD can never happen unnoticed again.
 *
 * <p>Background: OBS's {@code isReady} gate proves a scene/audio input is <em>configured</em>, but it
 * cannot tell whether the game-capture source is actually showing pixels. A game capture that fails
 * to hook the Dota window records a full match of pure black at the encoder's CBR bitrate — a normal
 * file size, so nothing downstream notices. (This exact failure shipped once: an empty-class window
 * match made {@code ms_find_window} select no window; see {@link ObsSceneConfigurer}.)
 *
 * <p>Mechanism: {@link #armCheck()} is called by {@link ObsEvents} the instant OBS confirms
 * {@code OUTPUT_STARTED}. It clears any prior warning and schedules a single, delayed, off-thread
 * check (the OBS event thread must never block). {@link #runCheck()} screenshots the Game Capture
 * source, measures its mean luminance, and if it is near-zero flips {@link ObsHealth#setCaptureBlack}
 * and pushes a fresh status frame so the UI can warn the user mid-match. The check is a pure
 * <em>detector</em>: it never touches the recording, and any sampling failure is treated as "unknown"
 * (no false alarm).
 *
 * <p>The delay lets the graphics hook settle (a cold NVENC/hook start can take several seconds — see
 * {@code ObsController.START_CONFIRM_TIMEOUT_MS}); by the check window Dota is rendering the game, so
 * a genuinely dark scene still carries a bright HUD/minimap and clears the threshold, while an
 * unhooked capture is uniformly black and trips it.
 */
@Component
public class CaptureBlackFrameGuard {

    private static final Logger log = LoggerFactory.getLogger(CaptureBlackFrameGuard.class);

    /**
     * Delay after OUTPUT_STARTED before sampling. Long enough for the game-capture hook + encoder to
     * settle (a cold hook can take several seconds), short enough to warn early in the match.
     */
    static final long CHECK_DELAY_MS = 8_000L;

    /**
     * Mean luminance (0..255) below which the capture is treated as black. Extremely conservative: an
     * unhooked capture is uniform 0, whereas any live Dota frame — even a night-time teamfight — keeps
     * a bright HUD, minimap, and shop, so its whole-frame mean sits far above this. The wide margin is
     * deliberate so a merely dark scene is never mislabelled.
     */
    static final double BLACK_LUMA_THRESHOLD = 3.0;

    private static final long SCREENSHOT_TIMEOUT_MS = 5_000L;
    /** Small sample: enough to average brightness, cheap to fetch and decode. */
    private static final int SAMPLE_WIDTH = 320;
    private static final int SAMPLE_HEIGHT = 180;
    private static final String IMAGE_FORMAT = "png";
    private static final int COMPRESSION_DEFAULT = -1;

    private final ObsController obs;
    private final ObsHealth health;
    private final EventPublisher events;

    /** Single-thread daemon scheduler: the delayed check runs off the OBS event thread. */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "capture-black-guard");
                        t.setDaemon(true);
                        return t;
                    });

    /** The most recently scheduled check, cancelled when a new recording re-arms. */
    private volatile ScheduledFuture<?> pending;

    public CaptureBlackFrameGuard(ObsController obs, ObsHealth health, EventPublisher events) {
        this.obs = obs;
        this.health = health;
        this.events = events;
    }

    /**
     * Arms a one-shot black-frame check for the recording that just started. Clears any stale warning
     * (a fresh recording is assumed good until proven black) and schedules {@link #runCheck()} after
     * {@link #CHECK_DELAY_MS}. Non-blocking: safe to call from the OBS event thread. Idempotent per
     * recording — a re-arm cancels the prior pending check.
     */
    public void armCheck() {
        health.setCaptureBlack(false);
        ScheduledFuture<?> prior = this.pending;
        if (prior != null) {
            prior.cancel(false);
        }
        try {
            this.pending = scheduler.schedule(this::runCheck, CHECK_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Scheduler shutting down (app quitting): nothing to check.
            log.debug("Black-frame check not scheduled: {}", e.toString());
        }
    }

    /**
     * Samples the Game Capture source and flags a black capture. Package-visible so a test can invoke
     * it directly without waiting on the scheduler. Skips silently when recording already ended or the
     * sample could not be taken (unknown != black — no false alarm).
     */
    void runCheck() {
        if (!health.isRecording()) {
            // The recording already stopped within the delay window; nothing to warn about.
            return;
        }
        Double luma = sampleGameCaptureLuma();
        if (luma == null) {
            log.debug("Black-frame check: could not sample the capture; skipping");
            return;
        }
        if (luma < BLACK_LUMA_THRESHOLD) {
            health.setCaptureBlack(true);
            log.warn(
                    "Capture appears BLACK (mean luma {} < {}): the Dota window is not being captured,"
                            + " so this recording will be a black screen. Check OBS Game Capture.",
                    String.format("%.2f", luma),
                    BLACK_LUMA_THRESHOLD);
            // Push a status frame now so the UI can warn mid-match instead of at the next poll.
            events.publishStatus();
        } else {
            health.setCaptureBlack(false);
            log.debug("Black-frame check ok (mean luma {})", String.format("%.2f", luma));
        }
    }

    /**
     * Screenshots the Game Capture source to a temp file, decodes it, and returns its mean luminance,
     * or {@code null} if OBS is not connected or the screenshot/decoding failed (treated as "unknown").
     * Package-visible and overridable purely as a test seam: a test can subclass and return a canned
     * luminance to exercise {@link #runCheck()}'s decision without a live OBS.
     */
    Double sampleGameCaptureLuma() {
        OBSRemoteController c = obs.connectedController();
        if (c == null) {
            return null;
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("dotarec-blackcheck-", "." + IMAGE_FORMAT);
            SaveSourceScreenshotResponse resp =
                    c.saveSourceScreenshot(
                            ObsSceneConfigurer.GAME_CAPTURE_INPUT,
                            IMAGE_FORMAT,
                            tmp.toString(),
                            SAMPLE_WIDTH,
                            SAMPLE_HEIGHT,
                            COMPRESSION_DEFAULT,
                            SCREENSHOT_TIMEOUT_MS);
            if (resp == null || !resp.isSuccessful()) {
                return null;
            }
            BufferedImage img = ImageIO.read(tmp.toFile());
            if (img == null) {
                return null;
            }
            return meanLuma(img);
        } catch (Exception e) {
            log.debug("Black-frame sample failed: {}", e.toString());
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // Temp file cleanup is best-effort.
                }
            }
        }
    }

    /**
     * Mean Rec.601 luma (0..255) over every pixel. Pure and static so the black/non-black decision can
     * be unit-tested directly against synthetic images, with no OBS.
     */
    static double meanLuma(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        long pixels = (long) w * h;
        if (pixels == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                sum += 0.299 * r + 0.587 * g + 0.114 * b;
            }
        }
        return sum / pixels;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
