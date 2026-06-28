package dev.dotarec.retention;

import dev.dotarec.config.SettingsStore;
import dev.dotarec.config.SettingsStore.StorageLocation;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchSummary;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tiered-storage placement: keeps recent VODs on the fast active recording drive and relocates the
 * oldest off it onto the configured archive drives, filling each up to its own cap.
 *
 * <p>Model (plan: Multi-drive storage). There is one ordered, capped location list: index 0 is the
 * active drive ({@code videoDir} / {@code retentionCapGb}, where OBS records); indices 1..N are the
 * archive drives ({@code storageLocations}). On each pass:
 *
 * <ol>
 *   <li>Ask the {@link RetentionSweeper} to enforce the global budget first — the budget is the SUM
 *       of all caps, so if total stored exceeds it the globally-oldest non-starred VOD is deleted
 *       (eviction is by age, NOT per-drive). After this, everything that remains fits in the
 *       allocations.</li>
 *   <li>While the active drive is over its own cap, move its OLDEST VOD to the first archive drive
 *       with headroom — headroom being {@code min(cap room, real free disk space)}, so a cap larger
 *       than the physical disk never overfills it (placement falls through to the next drive).</li>
 * </ol>
 *
 * <p>Newest recordings therefore stay on the fast drive; older ones spill to HDDs in list order. The
 * actively-recording file has no {@code matches} row until finalize, so it is never a move candidate.
 * Every step is best-effort: a failed move/stat is logged and retried on the next scheduled pass
 * (self-healing across restarts).
 */
@Component
public class RecordingArchiver {

    private static final Logger log = LoggerFactory.getLogger(RecordingArchiver.class);

    private static final long BYTES_PER_GB = 1024L * 1024 * 1024;
    /** Default active cap when settings are unreadable/unset, matching {@link SettingsStore}. */
    private static final int DEFAULT_CAP_GB = 50;
    /** Leave this much free on an archive drive after a move, so a drive is never filled to 0. */
    private static final long ARCHIVE_FREE_MARGIN_BYTES = 256L * 1024 * 1024;

    /**
     * Probes a directory's real usable free space. Pulled behind an interface so tests can inject a
     * deterministic value instead of depending on the host filesystem.
     */
    @FunctionalInterface
    public interface FreeSpaceProbe {
        long usableBytes(Path dir) throws IOException;
    }

    private final MatchRepository matches;
    private final SettingsStore settings;
    private final RetentionSweeper sweeper;
    private final FreeSpaceProbe freeSpace;
    private final StorageMaintenanceLock maintenanceLock;

    @Autowired
    public RecordingArchiver(
            MatchRepository matches,
            SettingsStore settings,
            RetentionSweeper sweeper,
            StorageMaintenanceLock maintenanceLock) {
        this(matches, settings, sweeper, dir -> Files.getFileStore(dir).getUsableSpace(),
                maintenanceLock);
    }

    /**
     * Backward-compatible test seam: inject a deterministic free-space probe; defaults a fresh
     * {@link StorageMaintenanceLock}. Existing tests that drive the archiver alone (no concurrent
     * sweeper) are unaffected by a private lock instance.
     */
    RecordingArchiver(
            MatchRepository matches,
            SettingsStore settings,
            RetentionSweeper sweeper,
            FreeSpaceProbe freeSpace) {
        this(matches, settings, sweeper, freeSpace, new StorageMaintenanceLock());
    }

    /** Test seam: inject both a deterministic free-space probe and the shared maintenance lock. */
    RecordingArchiver(
            MatchRepository matches,
            SettingsStore settings,
            RetentionSweeper sweeper,
            FreeSpaceProbe freeSpace,
            StorageMaintenanceLock maintenanceLock) {
        this.matches = matches;
        this.settings = settings;
        this.sweeper = sweeper;
        this.freeSpace = freeSpace;
        this.maintenanceLock = maintenanceLock;
    }

    /** Scheduled relocation pass, a little behind the boot so the core/OBS settle first. */
    @Scheduled(initialDelay = 30_000L, fixedDelay = 120_000L)
    public void archive() {
        try {
            archive(null);
        } catch (RuntimeException e) {
            // A scheduled pass must never throw out of the executor; log and let the next pass retry.
            log.warn("Archive pass failed: {}", e.toString());
        }
    }

    /**
     * Runs one relocation pass. Returns the match ids whose files were moved this pass (empty when
     * nothing needed moving). {@code protectedId} is forwarded to the sweeper so an actively-recording
     * match (if it ever has a row) is never deleted to free room.
     */
    public ArchiveResult archive(Long protectedId) {
        // Serialize against the retention sweeper's delete pass: both run on different scheduler-pool
        // threads and mutate the same VOD files/rows, so an unguarded interleave could have the sweeper
        // delete a file mid-copy or null a row this pass just repointed. The lock is reentrant, so the
        // nested sweeper.sweep() call below (on THIS thread, while already holding it) does not
        // self-deadlock.
        maintenanceLock.lock();
        try {
            // Step 1: enforce the global sum-of-caps budget first (deletes globally-oldest if over), so
            // what remains is guaranteed to fit in the allocations.
            sweeper.sweep(protectedId);

            List<Location> locations = locations();
            // Index 0 is the active drive; without archive drives there is nowhere to relocate to.
            if (locations.size() <= 1) {
                return new ArchiveResult(List.of());
            }
            Location active = locations.get(0);
            List<Location> archives = locations.subList(1, locations.size());

            // Account current per-location usage from the DB (rows still holding a file).
            Map<Location, Long> used = new HashMap<>();
            for (Location loc : locations) {
                used.put(loc, 0L);
            }
            List<MatchSummary> activeMovable = new ArrayList<>();
            for (MatchSummary m : matches.findAll()) {
                if (m.videoPath() == null || m.videoPath().isBlank()) {
                    continue;
                }
                Location loc = locationOf(m.videoPath(), locations);
                if (loc == null) {
                    continue; // file lives outside any configured drive (e.g. videoDir was changed)
                }
                used.merge(loc, sizeOf(m), Long::sum);
                if (loc == active && (protectedId == null || m.id() != protectedId)) {
                    activeMovable.add(m);
                }
            }
            // Move order: starred keepers FIRST (so they reach the safe archive drive soonest), then
            // oldest-first within each group. Eviction (deletion) is still age-only and skips starred —
            // this only changes which files get RELOCATED first when the active drive is over its cap.
            activeMovable.sort(
                    Comparator.comparingInt((MatchSummary m) -> m.starred() ? 0 : 1)
                            .thenComparingLong(RecordingArchiver::ageKey));
            Deque<MatchSummary> queue = new ArrayDeque<>(activeMovable);

            List<Long> moved = new ArrayList<>();
            int unplaced = 0;
            // Move active VODs out (starred first, then oldest) until the active drive is under its cap.
            while (used.get(active) > active.capBytes() && !queue.isEmpty()) {
                MatchSummary vod = queue.pollFirst();
                long size = sizeOf(vod);
                Location target = firstWithHeadroom(archives, used, size);
                if (target == null) {
                    // This VOD doesn't fit any archive drive right now (by cap or physical space). Do
                    // NOT break the whole pass — a smaller VOD later in the queue may still fit, so
                    // skip this one and keep draining. The deque shrinks each iteration, so the loop
                    // still terminates.
                    unplaced++;
                    continue;
                }
                if (moveToLocation(vod, target.dir())) {
                    used.merge(active, -size, Long::sum);
                    used.merge(target, size, Long::sum);
                    moved.add(vod.id());
                }
                // A failed move is left for the next pass; the loop still terminates (deque drains).
            }
            if (!moved.isEmpty()) {
                log.info("Archiver relocated {} recording(s) off the active drive: {}", moved.size(), moved);
            }
            if (unplaced > 0) {
                log.warn(
                        "{} recording(s) could not be placed on any archive drive this pass (all full by"
                            + " cap or physical space); leaving them on the active drive",
                        unplaced);
            }
            return new ArchiveResult(moved);
        } finally {
            maintenanceLock.unlock();
        }
    }

    /** First archive location whose cap room AND real free space can both hold {@code size}. */
    private Location firstWithHeadroom(List<Location> archives, Map<Location, Long> used, long size) {
        for (Location loc : archives) {
            if (used.get(loc) + size > loc.capBytes()) {
                continue; // over its allocated budget
            }
            long free;
            try {
                free = freeSpace.usableBytes(loc.dir());
            } catch (IOException | RuntimeException e) {
                // Can't read the drive (unplugged/missing): skip it rather than risk a failed move.
                log.debug("Free-space probe failed for {}: {}", loc.dir(), e.toString());
                continue;
            }
            if (free < size + ARCHIVE_FREE_MARGIN_BYTES) {
                continue; // physically can't fit even though the cap says there's room
            }
            return loc;
        }
        return null;
    }

    /**
     * Moves a VOD's {@code .mp4} (and thumbnail, into {@code <dir>/thumbs}) onto {@code targetDir} and
     * repoints the DB row. Returns false (leaving the row untouched) if the video move fails, so the
     * file is never lost and the next pass retries.
     *
     * <p>Crash-safety ordering (the cross-store SSD→HDD case): copy → verify size → {@code
     * updateVideoPath} → ONLY THEN delete the source. The DB is repointed to a file that already fully
     * exists before the original is removed, so a crash at any point leaves at worst a stale duplicate
     * the orphan scan reclaims — never a row pointing at a deleted file with the real bytes stranded
     * unreferenced. The thumbnail follows the same order: its source is deleted only after the single
     * {@code updateVideoPath} (which carries the new thumb path) commits.
     */
    private boolean moveToLocation(MatchSummary vod, Path targetDir) {
        Path src = Path.of(vod.videoPath());
        try {
            Files.createDirectories(targetDir);
            // Collision-proof destination: prefix the id so a move can never overwrite a DIFFERENT
            // match's archived VOD (OBS's per-second filenames could otherwise collide on a clock
            // anomaly). With the id prefix, REPLACE_EXISTING on the copy can only ever clobber a
            // leftover from THIS SAME match's prior interrupted move — which makes retries idempotent.
            Path dstVideo = targetDir.resolve(vod.id() + "-" + src.getFileName());
            // A same-filesystem rename is atomic and self-finalizing; repoint after it succeeds.
            // Otherwise copy+verify, leaving the source in place so the repoint below points at a file
            // that already exists before any delete.
            boolean videoRenamedInPlace = tryAtomicMove(src, dstVideo);

            // Stage the thumbnail copy the SAME way: for the cross-store path, copy it to the
            // destination (source kept) and remember whether to delete the source after the repoint.
            // Best-effort: a thumb-copy failure keeps the old thumb path (still on the source drive)
            // rather than losing the row's thumbnail — the video, the important bit, is moved.
            String newThumb = vod.thumbPath();
            Path thumbSrc = null;
            boolean thumbRenamedInPlace = false;
            if (vod.thumbPath() != null && !vod.thumbPath().isBlank()) {
                try {
                    thumbSrc = Path.of(vod.thumbPath());
                    Path thumbDir = targetDir.resolve("thumbs");
                    Files.createDirectories(thumbDir);
                    Path dstThumb = thumbDir.resolve(vod.id() + "-" + thumbSrc.getFileName());
                    thumbRenamedInPlace = tryAtomicMove(thumbSrc, dstThumb);
                    newThumb = dstThumb.toString();
                } catch (IOException | RuntimeException e) {
                    log.warn("Moved video for match {} but thumbnail copy failed: {}",
                            vod.id(), e.toString());
                    thumbSrc = null; // copy failed: do NOT delete the source thumb below
                }
            }

            // Repoint the row to the (already fully-written) destination(s) BEFORE deleting any source.
            matches.updateVideoPath(vod.id(), dstVideo.toString(), newThumb);

            // The row now references the destination; the source copies are safe to remove. (An atomic
            // rename already consumed the source, so only delete on the cross-store copy path.)
            if (!videoRenamedInPlace) {
                deleteQuietly(src);
            }
            if (thumbSrc != null && !thumbRenamedInPlace) {
                deleteQuietly(thumbSrc);
            }
            log.debug("Moved match {} -> {}", vod.id(), dstVideo);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not archive match {} ({} -> {}): {}",
                    vod.id(), src, targetDir, e.toString());
            return false;
        }
    }

    /**
     * Stages a file at {@code dst}. Tries an atomic rename first (same-filesystem fast path) and
     * returns {@code true} when it succeeds — the source is already gone, so the caller must NOT delete
     * it. On a cross-filesystem move (the normal SSD→HDD case) it copies and verifies the byte count,
     * LEAVING the source in place, and returns {@code false} so the caller deletes the source only
     * AFTER the DB repoint. This is the crux of crash-safety: the source is never unlinked before the
     * row points at a fully-written destination.
     *
     * <p>{@code REPLACE_EXISTING} is safe because {@code dst} carries the match id (see the caller): it
     * can only ever overwrite a leftover from this same match's prior interrupted move, making retries
     * idempotent.
     */
    private boolean tryAtomicMove(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (AtomicMoveNotSupportedException expected) {
            // Cross-store move: copy and verify the byte count; the source is deleted later, post-repoint.
        }
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        if (Files.size(dst) != Files.size(src)) {
            throw new IOException("Copy size mismatch moving " + src + " -> " + dst);
        }
        return false;
    }

    /** Deletes a now-redundant source file; a failed delete only leaves a stale duplicate (logged). */
    private void deleteQuietly(Path src) {
        try {
            Files.delete(src);
        } catch (IOException | RuntimeException e) {
            // The row already points at the destination, so a leftover source is harmless — the orphan
            // scan reclaims it. Log and move on rather than failing an already-committed move.
            log.warn("Archived match source {} could not be deleted (harmless duplicate): {}",
                    src, e.toString());
        }
    }

    /** Ordered location list: active drive first (videoDir/retentionCapGb), then each archive drive. */
    private List<Location> locations() {
        List<Location> out = new ArrayList<>();
        SettingsStore.Settings s = settings.get();
        String videoDir = s.videoDir;
        if (videoDir != null && !videoDir.isBlank()) {
            int capGb = s.retentionCapGb > 0 ? s.retentionCapGb : DEFAULT_CAP_GB;
            out.add(new Location(Path.of(videoDir), (long) capGb * BYTES_PER_GB));
        }
        if (s.storageLocations != null) {
            for (StorageLocation loc : s.storageLocations) {
                if (loc == null || loc.path() == null || loc.path().isBlank() || loc.capGb() <= 0) {
                    continue;
                }
                out.add(new Location(Path.of(loc.path()), (long) loc.capGb() * BYTES_PER_GB));
            }
        }
        return out;
    }

    /** The configured location a stored video path lives under, or null if none. */
    private Location locationOf(String videoPath, List<Location> locations) {
        Path file;
        try {
            file = Path.of(videoPath).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
        String fileStr = file.toString().toLowerCase();
        for (Location loc : locations) {
            String dirStr = loc.dir().toAbsolutePath().normalize().toString().toLowerCase();
            // Prefix on the normalized path string (Windows paths are case-insensitive). Append a
            // separator so "C:\\vid" doesn't match a sibling "C:\\video2\\..." path.
            String dirPrefix = dirStr.endsWith(java.io.File.separator) ? dirStr : dirStr + java.io.File.separator;
            if (fileStr.startsWith(dirPrefix)) {
                return loc;
            }
        }
        return null;
    }

    /** Age sort key: the recording's play time, falling back to its creation time. */
    private static long ageKey(MatchSummary m) {
        return m.playedAt() != null ? m.playedAt() : m.createdAt();
    }

    private long sizeOf(MatchSummary m) {
        if (m.videoPath() != null && !m.videoPath().isBlank()) {
            try {
                return Files.size(Path.of(m.videoPath()));
            } catch (IOException | RuntimeException e) {
                log.debug("Could not stat {} during archive: {}", m.videoPath(), e.toString());
            }
        }
        return m.fileSizeBytes() != null ? m.fileSizeBytes() : 0L;
    }

    /** One drive in the ordered fill list: its directory and its cap in bytes. */
    private record Location(Path dir, long capBytes) {}

    /** Outcome of a pass: the match ids whose files were relocated. */
    public record ArchiveResult(List<Long> movedIds) {}
}
