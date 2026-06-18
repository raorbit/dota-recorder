package dev.dotarec.retention;

import dev.dotarec.bridge.EventPublisher;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enforces the disk budget for recorded VODs (modeled on Warcraft Recorder).
 *
 * <p>Policy: keep total stored video under the {@code retentionCapGb} cap (default 50 GiB). When
 * over cap, delete the oldest non-starred recordings first -- the .mp4 and its thumbnail -- and null
 * the row's {@code video_path}/{@code thumb_path}/{@code file_size_bytes} while KEEPING the row, so
 * its markers/stats survive as a browsable record without a playable clip. Starred recordings are
 * never deleted, so the cap can be exceeded if the user stars enough material (by design).
 *
 * <p>Guard: the actively-recording match must never have its file deleted mid-write. There is no
 * live-session source yet, so {@link #sweep(Long)} takes an optional protected id (default none via
 * the {@link #sweep()} entry point). TODO: wire the FSM's active session id here once it exists.
 *
 * <p>Events: a completed sweep publishes {@code retention.swept} with {@code {freedBytes, deletedIds}};
 * a pre-record low-disk warning publishes an error frame {@code {scope:"disk", ...}}. The free-space
 * check WARNS only -- it never blocks a recording.
 */
@Component
public class RetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweeper.class);

    private static final long BYTES_PER_GB = 1024L * 1024 * 1024;
    /** Default cap when settings are unreadable / unset, matching {@link SettingsStore}. */
    private static final int DEFAULT_CAP_GB = 50;
    /** Warn before a record if free disk would dip under this (one large match's worth). */
    private static final long LOW_DISK_THRESHOLD_BYTES = 5L * BYTES_PER_GB;

    private final MatchRepository matches;
    private final SettingsStore settings;
    private final EventPublisher events;

    public RetentionSweeper(MatchRepository matches, SettingsStore settings, EventPublisher events) {
        this.matches = matches;
        this.settings = settings;
        this.events = events;
    }

    /** Scheduled hourly sweep with no protected match (nothing is actively recording from here). */
    @Scheduled(fixedDelay = 3_600_000L)
    public void sweep() {
        sweep(null);
    }

    /**
     * Runs one retention pass. While total stored video exceeds the cap, deletes the oldest
     * non-starred recording's files and nulls its path columns, until under cap or no candidate
     * remains. The {@code protectedId} (e.g. the actively-recording match) is skipped even if it is
     * the oldest, so an in-progress .mp4 is never deleted.
     *
     * @param protectedId match id to never delete, or null for none
     * @return result describing freed bytes and the swept ids
     */
    public SweepResult sweep(Long protectedId) {
        long capBytes = capBytes();
        long total = matches.totalVideoBytes();
        if (total <= capBytes) {
            return SweepResult.empty(total, capBytes);
        }

        List<MatchSummary> candidates = matches.findSweepCandidates(); // oldest first
        List<Long> deletedIds = new ArrayList<>();
        long freed = 0L;

        for (MatchSummary m : candidates) {
            if (total <= capBytes) {
                break;
            }
            if (protectedId != null && m.id() == protectedId) {
                continue; // never delete the actively-recording match's file
            }
            long size = m.fileSizeBytes() != null ? m.fileSizeBytes() : 0L;
            deleteFileQuietly(m.videoPath());
            deleteFileQuietly(m.thumbPath());
            matches.nullVideoPath(m.id());
            deletedIds.add(m.id());
            freed += size;
            total -= size;
        }

        if (!deletedIds.isEmpty()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("freedBytes", freed);
            payload.put("deletedIds", deletedIds);
            events.publish("retention.swept", payload);
            log.info("Retention sweep freed {} bytes across {} recordings (cap {} bytes)",
                    freed, deletedIds.size(), capBytes);
        }
        return new SweepResult(freed, deletedIds, total, capBytes);
    }

    /**
     * Pre-record free-space check. Computes free bytes on the video directory's filesystem and, if
     * below the low-disk threshold, publishes a {@code {scope:"disk"}} error frame and returns a
     * warning. Returns null when disk is healthy. NEVER blocks recording -- it only warns.
     *
     * @return a warning string when low on disk, or null when healthy / unknown
     */
    public String checkFreeSpaceWarning() {
        Path videoDir = videoDir();
        long free;
        try {
            free = Files.getFileStore(videoDir).getUsableSpace();
        } catch (IOException e) {
            // Can't read the filesystem (e.g. dir not yet created): don't warn, don't block.
            log.debug("Free-space check skipped for {}", videoDir, e);
            return null;
        }
        if (free >= LOW_DISK_THRESHOLD_BYTES) {
            return null;
        }
        String warning = "Low disk space: " + free + " bytes free on the video drive (under "
                + LOW_DISK_THRESHOLD_BYTES + " bytes). Recording continues; old VODs will be pruned.";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", "disk");
        payload.put("freeBytes", free);
        payload.put("thresholdBytes", LOW_DISK_THRESHOLD_BYTES);
        payload.put("message", warning);
        events.publish("error", payload);
        log.warn(warning);
        return warning;
    }

    private long capBytes() {
        int capGb;
        try {
            capGb = settings.get().retentionCapGb;
        } catch (RuntimeException e) {
            capGb = DEFAULT_CAP_GB;
        }
        if (capGb <= 0) {
            capGb = DEFAULT_CAP_GB;
        }
        return (long) capGb * BYTES_PER_GB;
    }

    private Path videoDir() {
        String dir = settings.get().videoDir;
        return Path.of(dir != null && !dir.isBlank() ? dir : ".");
    }

    private void deleteFileQuietly(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            boolean removed = Files.deleteIfExists(Path.of(path));
            if (removed) {
                log.debug("Deleted {}", path);
            }
        } catch (IOException | RuntimeException e) {
            // A locked/missing file must not abort the sweep; the row is still pruned so the budget
            // accounting stays correct and we won't retry the same file forever.
            log.warn("Could not delete {} during retention sweep: {}", path, e.toString());
        }
    }

    /**
     * Outcome of a sweep. {@code freedBytes}/{@code deletedIds} describe what was removed;
     * {@code totalAfterBytes} and {@code capBytes} let callers/tests assert the budget state.
     */
    public record SweepResult(long freedBytes, List<Long> deletedIds, long totalAfterBytes,
                              long capBytes) {
        static SweepResult empty(long total, long cap) {
            return new SweepResult(0L, List.of(), total, cap);
        }
    }
}
