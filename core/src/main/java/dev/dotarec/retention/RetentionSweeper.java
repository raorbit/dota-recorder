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

    /**
     * Probes a directory's filesystem TOTAL capacity. Pulled behind an interface (mirroring
     * {@link RecordingArchiver.FreeSpaceProbe}) so tests can inject a deterministic disk size instead
     * of depending on the host filesystem. Used to clamp a configured cap to what the disk can hold.
     */
    @FunctionalInterface
    public interface TotalSpaceProbe {
        long totalBytes(Path dir) throws IOException;
    }

    private final MatchRepository matches;
    private final SettingsStore settings;
    private final EventPublisher events;
    private final StorageMaintenanceLock maintenanceLock;
    private final TotalSpaceProbe totalSpace;

    @org.springframework.beans.factory.annotation.Autowired
    public RetentionSweeper(
            MatchRepository matches,
            SettingsStore settings,
            EventPublisher events,
            StorageMaintenanceLock maintenanceLock) {
        this(matches, settings, events, maintenanceLock,
                dir -> Files.getFileStore(dir).getTotalSpace());
    }

    /**
     * Backward-compatible constructor for existing tests: defaults a fresh {@link
     * StorageMaintenanceLock} (the sweeper is the sole lock holder in those tests, so a private
     * instance is fine) and a real total-space probe.
     */
    public RetentionSweeper(MatchRepository matches, SettingsStore settings, EventPublisher events) {
        this(matches, settings, events, new StorageMaintenanceLock(),
                dir -> Files.getFileStore(dir).getTotalSpace());
    }

    /** Test seam: inject a deterministic total-space probe (and the shared lock). */
    RetentionSweeper(
            MatchRepository matches,
            SettingsStore settings,
            EventPublisher events,
            StorageMaintenanceLock maintenanceLock,
            TotalSpaceProbe totalSpace) {
        this.matches = matches;
        this.settings = settings;
        this.events = events;
        this.maintenanceLock = maintenanceLock;
        this.totalSpace = totalSpace;
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
        // Serialize against the archiver's move pass: the two run on different scheduler-pool threads
        // and both mutate the same VOD files/rows, so an unguarded interleave could delete a file the
        // archiver is mid-copy of, or null a row the archiver just repointed. The lock is reentrant, so
        // RecordingArchiver.archive() calling sweep() on its OWN thread (while already holding it) is
        // safe.
        maintenanceLock.lock();
        try {
            long capBytes = capBytes();
            long total = totalStoredVideoBytes();
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
                long size = videoSizeBytes(m);
                boolean videoGone = deleteFileQuietly(m.videoPath());
                boolean thumbGone = deleteFileQuietly(m.thumbPath());
                if (!videoGone) {
                    log.warn("Retention sweep left match {} intact because video deletion failed", m.id());
                    continue;
                }
                total -= size;
                if (!thumbGone) {
                    log.warn(
                            "Retention sweep could not delete thumbnail for match {}; pruning video row anyway",
                            m.id());
                }
                matches.nullVideoPath(m.id());
                deletedIds.add(m.id());
                freed += size;
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
        } finally {
            maintenanceLock.unlock();
        }
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

    /**
     * Total disk budget = sum of every configured location's cap (the active recording drive's
     * {@code retentionCapGb} PLUS each archive drive's {@code capGb}). Eviction is global, not
     * per-drive: while stored video exceeds this sum, the oldest non-starred VOD is pruned wherever
     * it lives. Per-drive caps govern only WHERE the archiver places files; age governs deletion.
     *
     * <p>Each location's contribution is CLAMPED to {@code min(configuredCap, drive's real total
     * capacity)}. A cap larger than the disk would otherwise inflate the global budget above what the
     * disks can physically hold — so {@code totalStored} could never reach it and eviction would be
     * disabled entirely, letting the active drive grow unbounded. Clamping keeps the budget honest. If
     * a drive can't be stat'd (unplugged/not yet created), we fall back to its raw configured cap —
     * you can't clamp what you can't measure, and under-counting is safer than aborting the sweep.
     */
    private long capBytes() {
        int activeCapGb;
        String videoDir;
        List<SettingsStore.StorageLocation> archives;
        try {
            SettingsStore.Settings s = settings.get();
            activeCapGb = s.retentionCapGb;
            videoDir = s.videoDir;
            archives = s.storageLocations;
        } catch (RuntimeException e) {
            return (long) DEFAULT_CAP_GB * BYTES_PER_GB;
        }
        if (activeCapGb <= 0) {
            activeCapGb = DEFAULT_CAP_GB;
        }
        // Location 0 (active drive) is clamped too: a 500 GiB cap on a 256 GiB SSD must not pretend
        // there's 500 GiB of budget there.
        long sumBytes = clampedCapBytes(videoDir, activeCapGb);
        if (archives != null) {
            for (SettingsStore.StorageLocation loc : archives) {
                if (loc != null && loc.capGb() > 0) {
                    sumBytes += clampedCapBytes(loc.path(), loc.capGb());
                }
            }
        }
        return sumBytes;
    }

    /**
     * A single location's budget in bytes, clamped to the drive's physical total capacity. Falls back
     * to the raw configured cap when the directory is blank/unparseable or can't be stat'd.
     */
    private long clampedCapBytes(String dir, int capGb) {
        long capBytes = (long) capGb * BYTES_PER_GB;
        if (dir == null || dir.isBlank()) {
            return capBytes;
        }
        Path path;
        try {
            path = Path.of(dir);
        } catch (RuntimeException e) {
            return capBytes;
        }
        try {
            long total = totalSpace.totalBytes(path);
            return Math.min(capBytes, total);
        } catch (IOException | RuntimeException e) {
            // Can't measure (drive unplugged / dir not yet created): keep the raw cap rather than
            // clamp to zero, which would make the global budget collapse and over-evict.
            log.debug("Total-space probe failed for {}; using raw cap: {}", path, e.toString());
            return capBytes;
        }
    }

    private Path videoDir() {
        String dir = settings.get().videoDir;
        return Path.of(dir != null && !dir.isBlank() ? dir : ".");
    }

    private long totalStoredVideoBytes() {
        long total = 0L;
        for (MatchSummary m : matches.findAll()) {
            if (m.videoPath() == null || m.videoPath().isBlank()) {
                continue;
            }
            total += videoSizeBytes(m);
        }
        return total;
    }

    private long videoSizeBytes(MatchSummary m) {
        String path = m.videoPath();
        if (path == null || path.isBlank()) {
            return 0L;
        }
        try {
            return Files.size(Path.of(path));
        } catch (IOException | RuntimeException e) {
            // Prefer real filesystem size, but fall back to the DB snapshot when a transient stat
            // failure would otherwise hide an over-budget row from pruning.
            log.warn("Could not stat {} during retention sweep: {}", path, e.toString());
            return m.fileSizeBytes() != null ? m.fileSizeBytes() : 0L;
        }
    }

    private boolean deleteFileQuietly(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        try {
            boolean removed = Files.deleteIfExists(Path.of(path));
            if (removed) {
                log.debug("Deleted {}", path);
            }
            return !Files.exists(Path.of(path));
        } catch (IOException | RuntimeException e) {
            // A locked/missing file must not abort the sweep. Return false so the row keeps its path
            // and the next sweep retries instead of forgetting a file that may still be on disk.
            log.warn("Could not delete {} during retention sweep: {}", path, e.toString());
            return false;
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
