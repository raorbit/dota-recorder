package dev.dotarec.clip;

import dev.dotarec.bridge.EventPublisher;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.data.ClipRepository;
import dev.dotarec.data.ClipRow;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchSummary;
import dev.dotarec.retention.StorageMaintenanceLock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Creates clips (sub-range .mp4s of a parent match's recording) and drives their async generation.
 *
 * <p>Two entry points mint a {@code pending} {@link ClipRow}: {@link #createManual} (a user carve)
 * and {@link #createAuto} (the finalize hook, e.g. a rampage). Both publish {@code clip.created},
 * then fire-and-forget {@link #generateAsync} onto the bounded {@code clipExecutor}, which cuts the
 * VOD with {@link Clipper} and flips the row to {@code ready}/{@code failed}.
 *
 * <p>The cut takes the {@link StorageMaintenanceLock} around reading the source so the retention
 * sweeper/archiver cannot delete or relocate the parent VOD mid-cut, and re-reads the parent's
 * current {@code video_path} under the lock (it may already have been pruned or moved).
 *
 * <p>{@code @Async} runs through the Spring proxy; {@link #createManual}/{@link #createAuto} call the
 * async method via a lazy self-reference ({@code self}) so the dispatch is not a self-invocation that
 * would bypass the proxy and run synchronously.
 */
@Service
public class ClipService {

    private static final Logger log = LoggerFactory.getLogger(ClipService.class);

    /** Hard ceiling on a clip's length; bounds the case where the parent match has a null duration. */
    private static final double MAX_CLIP_SECONDS = 4 * 60 * 60;
    /** Hard ceiling on a clip label's length. */
    private static final int MAX_LABEL_CHARS = 200;

    private final ClipRepository clips;
    private final MatchRepository matches;
    private final Clipper clipper;
    private final EventPublisher events;
    private final SettingsStore settings;
    private final AppPaths paths;
    private final StorageMaintenanceLock maintenanceLock;
    private final ClipService self;

    public ClipService(ClipRepository clips, MatchRepository matches, Clipper clipper,
                       EventPublisher events, SettingsStore settings, AppPaths paths,
                       StorageMaintenanceLock maintenanceLock, @Lazy ClipService self) {
        this.clips = clips;
        this.matches = matches;
        this.clipper = clipper;
        this.events = events;
        this.settings = settings;
        this.paths = paths;
        this.maintenanceLock = maintenanceLock;
        this.self = self;
    }

    /**
     * Carves a user-defined clip {@code [startS, endS]} out of a match's recording. Validates the
     * parent match exists and still has a video on disk, clamps the range to {@code [0, durationS]},
     * inserts a {@code pending} row, publishes {@code clip.created}, and dispatches async generation.
     *
     * @return the new clip's id
     * @throws IllegalArgumentException if the match is missing, has no video, or the range is empty
     */
    public long createManual(long parentMatchId, double startS, double endS, String label) {
        return create(parentMatchId, startS, endS, "manual", null, label);
    }

    /**
     * Mints an auto clip (e.g. a rampage) over {@code [startS, endS]} of a match's recording. Same
     * validation/dispatch as {@link #createManual} but {@code kind="auto"} with a {@code triggerReason}.
     * Called by the finalize hook after a match's markers are tagged.
     *
     * @return the new clip's id
     * @throws IllegalArgumentException if the match is missing, has no video, or the range is empty
     */
    public long createAuto(long parentMatchId, double startS, double endS, String triggerReason) {
        return create(parentMatchId, startS, endS, "auto", triggerReason, null);
    }

    private long create(long parentMatchId, double startS, double endS, String kind,
                        String triggerReason, String label) {
        MatchSummary match = matches.findById(parentMatchId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cannot create clip: no match " + parentMatchId));
        if (match.videoPath() == null || match.videoPath().isBlank()) {
            throw new IllegalArgumentException(
                    "Cannot create clip: match " + parentMatchId + " has no recorded video");
        }
        // Reject non-finite offsets before the clamping math: the empty-range check below fails for NaN
        // (NaN <= NaN is false), so a NaN would otherwise slip through to ffmpeg as "NaN".
        if (!Double.isFinite(startS) || !Double.isFinite(endS)) {
            throw new IllegalArgumentException(
                    "Cannot create clip: non-finite range [" + startS + ", " + endS + "] for match "
                            + parentMatchId);
        }
        if (label != null && label.length() > MAX_LABEL_CHARS) {
            throw new IllegalArgumentException(
                    "Cannot create clip: label exceeds " + MAX_LABEL_CHARS + " chars (" + label.length()
                            + ") for match " + parentMatchId);
        }

        // Clamp to the recorded range. A null/absent duration just leaves the upper bound open.
        double lower = Math.max(0.0, Math.min(startS, endS));
        double upper = Math.max(startS, endS);
        if (match.durationS() != null) {
            double dur = match.durationS();
            lower = Math.max(0.0, Math.min(lower, dur));
            upper = Math.min(upper, dur);
        }
        if (upper <= lower) {
            throw new IllegalArgumentException(
                    "Cannot create clip: empty range [" + startS + ", " + endS + "] for match "
                            + parentMatchId);
        }
        // Bound the length — chiefly for a parent with a null durationS (no upper clamp above).
        if (upper - lower > MAX_CLIP_SECONDS) {
            throw new IllegalArgumentException(
                    "Cannot create clip: range " + (upper - lower) + "s exceeds max " + MAX_CLIP_SECONDS
                            + "s for match " + parentMatchId);
        }

        long clipId = clips.insert(parentMatchId, kind, triggerReason, lower, upper, label,
                null, null, null, "pending", null, System.currentTimeMillis());
        clips.findById(clipId).ifPresent(row -> events.publish("clip.created", row));
        try {
            self.generateAsync(clipId);
        } catch (TaskRejectedException e) {
            // Executor saturated — the row is already persisted as pending, so ClipQueue will
            // pick it up within its sweep interval. Never block the caller (may be the FSM thread).
            log.debug("Clip {} dispatch rejected (queue full); left pending for ClipQueue", clipId);
        }
        return clipId;
    }

    /**
     * Renders a clip's .mp4 on the {@code clipExecutor}. Re-reads the row and the parent match's
     * current video (which may have moved or been pruned) under the {@link StorageMaintenanceLock},
     * cuts it with {@link Clipper} into {@code <videoDir>/clips/<matchId>-clip-<clipId>.mp4}, and
     * flips the row to {@code ready}/{@code failed} — publishing {@code clip.progress} then
     * {@code clip.ready} so the UI can refresh the match's clip list.
     */
    @Async("clipExecutor")
    public void generateAsync(long clipId) {
        Optional<ClipRow> found = clips.findById(clipId);
        if (found.isEmpty()) {
            log.warn("generateAsync: clip {} disappeared before generation", clipId);
            return;
        }
        ClipRow clip = found.get();
        long parentMatchId = clip.parentMatchId();

        // Atomically claim the row (pending -> generating). If we don't win, another dispatch already
        // took it (the create dispatch racing the ClipQueue retry sweep) or it's already done/deleted —
        // skip, so a completed clip is never re-cut and its output overwritten.
        if (!clips.claimForGeneration(clipId)) {
            log.debug("Clip {} not claimable (already generating/ready/failed or removed); skipping", clipId);
            return;
        }
        events.publish("clip.progress", progress(clipId, parentMatchId, 0));

        // The CUT reads the parent VOD, so it runs under the lock so the sweeper/archiver can't move or
        // delete the source mid-cut. The thumbnail + finalize below read the GENERATED clip (our own
        // output), not the parent VOD, so they run OUTSIDE the lock to avoid needlessly blocking
        // retention during a second ffmpeg process.
        // Guard a corrupt row that bypassed create()'s range validation: a degenerate/inverted range
        // would otherwise be handed to ffmpeg as a zero/negative duration. Fail it rather than cut.
        if (clip.endOffsetS() <= clip.startOffsetS()) {
            failClip(clipId, parentMatchId, "degenerate clip range ["
                    + clip.startOffsetS() + ", " + clip.endOffsetS() + "]");
            return;
        }

        Clipper.Result result;
        double duration;
        // Hoisted so the catch block can clean up a partial ffmpeg output if the cut throws mid-write.
        Path out = null;
        maintenanceLock.lock();
        try {
            // Re-read the parent's current path under the lock: the sweeper/archiver may have moved or
            // pruned the VOD since the row was inserted.
            Optional<MatchSummary> match = matches.findById(parentMatchId);
            String videoPath = match.map(MatchSummary::videoPath).orElse(null);
            if (videoPath == null || videoPath.isBlank()) {
                failClip(clipId, parentMatchId,
                        "parent match " + parentMatchId + " no longer has a video on disk");
                return;
            }
            Path source = Path.of(videoPath);
            if (!Files.isRegularFile(source)) {
                failClip(clipId, parentMatchId, "parent video missing on disk: " + source);
                return;
            }

            Path outDir = videoDir().resolve("clips");
            Files.createDirectories(outDir);
            out = outDir.resolve(parentMatchId + "-clip-" + clipId + ".mp4");

            duration = clip.endOffsetS() - clip.startOffsetS();
            result = clipper.clip(source, clip.startOffsetS(), duration, out);
        } catch (Exception e) {
            log.warn("Failed to generate clip {} for match {}: {}",
                    clipId, parentMatchId, e.getMessage(), e);
            // The cut may have left a partial .mp4 behind; failClip only nulls video_path, so unlink it.
            deleteQuietly(out);
            failClip(clipId, parentMatchId, e.getMessage());
            return;
        } finally {
            maintenanceLock.unlock();
        }

        // Grab a thumbnail at the clip's midpoint, reading from the generated clip file (so the seek
        // offset is relative to the clip, not the parent VOD). A thumbnail failure must never fail the
        // clip — log it and leave thumb_path null.
        String thumbPath = null;
        Path thumbOut = null;
        try {
            Path thumbDir = videoDir().resolve("clips").resolve("thumbs");
            Files.createDirectories(thumbDir);
            thumbOut = thumbDir.resolve(parentMatchId + "-clip-" + clipId + ".jpg");
            Path thumb = clipper.thumbnail(result.output(), duration / 2.0, thumbOut);
            thumbPath = thumb.toString();
        } catch (Exception e) {
            // A failed thumbnail must not fail the clip, but don't leave a partial/zero-byte .jpg behind.
            log.warn("Failed to generate thumbnail for clip {} (match {}): {}",
                    clipId, parentMatchId, e.getMessage());
            deleteQuietly(thumbOut);
        }

        try {
            int updated = clips.updateStatus(clipId, "ready", result.output().toString(),
                    result.sizeBytes(), thumbPath, null);
            if (updated == 0) {
                // The row was deleted (user removed the clip) while we were generating. Don't leak the
                // output we just wrote, and don't publish a ready event for a clip that no longer exists.
                log.info("Clip {} (match {}) deleted during generation; discarding orphaned output {}",
                        clipId, parentMatchId, result.output());
                deleteQuietly(result.output());
                if (thumbPath != null) {
                    deleteQuietly(Path.of(thumbPath));
                }
                return;
            }
            log.info("Generated clip {} for match {} -> {} ({} bytes)",
                    clipId, parentMatchId, result.output(), result.sizeBytes());
            events.publish("clip.ready",
                    ready(clipId, parentMatchId, "ready", result.output().toString()));
        } catch (Exception e) {
            log.warn("Failed to finalize clip {} for match {}: {}",
                    clipId, parentMatchId, e.getMessage(), e);
            // The status update failed, so the row won't reference these files — unlink the orphaned
            // output (and thumbnail) rather than leaking them on disk.
            deleteQuietly(result.output());
            if (thumbPath != null) {
                deleteQuietly(Path.of(thumbPath));
            }
            failClip(clipId, parentMatchId, e.getMessage());
        }
    }

    private void failClip(long clipId, long parentMatchId, String error) {
        // The status write itself can fail (e.g. a back-to-back SQLITE_BUSY under WAL contention). Swallow
        // it here so the exception never escapes the @Async method — otherwise the row would stay stuck in
        // 'generating' forever. ClipQueue.sweep re-pends such a wedged row past its stale cutoff.
        try {
            clips.updateStatus(clipId, "failed", null, null, null, error);
        } catch (Exception e) {
            log.warn("Failed to mark clip {} (match {}) failed; ClipQueue will re-pend it once stale: {}",
                    clipId, parentMatchId, e.getMessage());
            return;
        }
        events.publish("clip.ready", ready(clipId, parentMatchId, "failed", null));
    }

    /** Best-effort unlink of an orphaned clip output; a missing/locked file is logged, never thrown. */
    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Could not delete orphaned clip file {}: {}", p, e.getMessage());
        }
    }

    /** Resolves the recording dir from settings, mirroring {@code ThumbnailService}'s blank fallback. */
    private Path videoDir() {
        String dir = settings.get().videoDir;
        return (dir == null || dir.isBlank()) ? paths.videoDir() : Path.of(dir);
    }

    private static Map<String, Object> progress(long clipId, long parentMatchId, int percent) {
        Map<String, Object> m = new HashMap<>(3);
        m.put("clipId", clipId);
        m.put("parentMatchId", parentMatchId);
        m.put("percent", percent);
        return m;
    }

    private static Map<String, Object> ready(long clipId, long parentMatchId, String status,
                                             String videoPath) {
        Map<String, Object> m = new HashMap<>(4);
        m.put("clipId", clipId);
        m.put("parentMatchId", parentMatchId);
        m.put("status", status);
        m.put("videoPath", videoPath);
        return m;
    }
}
