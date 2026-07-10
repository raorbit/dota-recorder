package dev.dotarec.retention;

import dev.dotarec.bridge.EventPublisher;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.config.StorageRoots;
import dev.dotarec.data.ClipRepository;
import dev.dotarec.data.ClipRow;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * a low-disk warning (checked on every scheduled pass) publishes an error frame
 * {@code {scope:"disk", ...}}. The free-space check WARNS only -- it never blocks a recording.
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
     * Minimum wall-clock age of a FIRST missing-file observation before the destructive row
     * reconcile may run. Pass count alone is no time guarantee: every archiver pass (~2 min
     * fixedDelay) calls {@link #sweep(Long)} and ticks {@link #sweepPass}, so "a later pass"
     * arrives in ~2 minutes — mid-way through exactly the scenario the miss registry protects (a
     * starred VOD being cut-pasted to a slow USB drive and moved back). Thirty minutes gives such
     * a move time to finish before its row is reconciled.
     */
    private static final long MISS_CONFIRMATION_MIN_AGE_MS = 30L * 60 * 1000;

    /**
     * Probes a directory's filesystem TOTAL capacity. Pulled behind an interface (mirroring
     * {@link RecordingArchiver.FreeSpaceProbe}) so tests can inject a deterministic disk size instead
     * of depending on the host filesystem. Used to clamp a configured cap to what the disk can hold.
     */
    @FunctionalInterface
    public interface TotalSpaceProbe {
        long totalBytes(Path dir) throws IOException;
    }

    /**
     * Probes a directory's real usable free space. Pulled behind an interface (mirroring
     * {@link RecordingArchiver.FreeSpaceProbe}) so a test can drive {@link #checkFreeSpaceWarning()}'s
     * low-disk branch deterministically instead of depending on the host filesystem's actual free
     * space.
     */
    @FunctionalInterface
    public interface UsableSpaceProbe {
        long usableBytes(Path dir) throws IOException;
    }

    /**
     * Probes one stored file's on-disk size ({@link Files#size}). Pulled behind an interface
     * (mirroring the space probes above) so a test can drive {@link #statFileBytes}'s
     * confirmed-missing and ambiguous-failure branches deterministically.
     */
    @FunctionalInterface
    public interface FileSizeProbe {
        long sizeBytes(Path file) throws IOException;
    }

    /**
     * Supplies wall-clock epoch millis for the miss-confirmation age gate. Pulled behind an
     * interface (mirroring the probes above) so tests can advance time deterministically instead
     * of sleeping through the {@link #MISS_CONFIRMATION_MIN_AGE_MS} floor.
     */
    @FunctionalInterface
    public interface MillisClock {
        long millis();
    }

    private final MatchRepository matches;
    private final ClipRepository clips;
    private final SettingsStore settings;
    private final EventPublisher events;
    private final StorageMaintenanceLock maintenanceLock;
    private final TotalSpaceProbe totalSpace;
    private final UsableSpaceProbe usableSpace;
    private final FileSizeProbe fileSize;
    private final MillisClock clock;

    /**
     * Miss registry backing the confirmed-missing rule: row key
     * ({@code "match:"/"clip:" + id + "|" + path}) → the sweep pass and wall-clock time that FIRST
     * observed the file missing. The destructive row reconcile ({@link #reconcileMissingMatchVideo}
     * / {@link #dropMissingClip}) runs only when a miss persists into a LATER pass at least
     * {@link #MISS_CONFIRMATION_MIN_AGE_MS} after that first observation — archiver-driven passes
     * (~2 min apart) measure and refund but can never fast-confirm a destructive reconcile. A file
     * observed present again clears its entry, and entries whose row no longer carries the
     * snapshotted path are pruned every measurement. In-memory on purpose: a restart just restarts
     * the confirmation clock, which only delays a reconcile. Guarded by the maintenance lock —
     * every reader/writer runs inside {@link #sweep(Long)}.
     */
    private final Map<String, FirstMiss> firstMiss = new HashMap<>();
    /**
     * Monotonic sweep-pass counter behind {@link #firstMiss}; ticks on EVERY {@link #sweep(Long)}
     * entry (archiver passes included). Guarded by the maintenance lock.
     */
    private long sweepPass;

    @org.springframework.beans.factory.annotation.Autowired
    public RetentionSweeper(
            MatchRepository matches,
            ClipRepository clips,
            SettingsStore settings,
            EventPublisher events,
            StorageMaintenanceLock maintenanceLock) {
        this(matches, clips, settings, events, maintenanceLock,
                dir -> Files.getFileStore(dir).getTotalSpace(),
                dir -> Files.getFileStore(dir).getUsableSpace());
    }

    /**
     * Backward-compatible constructor for existing tests: defaults a fresh {@link
     * StorageMaintenanceLock} (the sweeper is the sole lock holder in those tests, so a private
     * instance is fine) and real total/usable-space probes.
     */
    public RetentionSweeper(MatchRepository matches, ClipRepository clips, SettingsStore settings,
                            EventPublisher events) {
        this(matches, clips, settings, events, new StorageMaintenanceLock(),
                dir -> Files.getFileStore(dir).getTotalSpace(),
                dir -> Files.getFileStore(dir).getUsableSpace());
    }

    /** Test seam: inject a deterministic total-space probe (and the shared lock); real usable-space. */
    RetentionSweeper(
            MatchRepository matches,
            ClipRepository clips,
            SettingsStore settings,
            EventPublisher events,
            StorageMaintenanceLock maintenanceLock,
            TotalSpaceProbe totalSpace) {
        this(matches, clips, settings, events, maintenanceLock, totalSpace,
                dir -> Files.getFileStore(dir).getUsableSpace());
    }

    /** Test seam: inject deterministic total- AND usable-space probes (and the shared lock). */
    RetentionSweeper(
            MatchRepository matches,
            ClipRepository clips,
            SettingsStore settings,
            EventPublisher events,
            StorageMaintenanceLock maintenanceLock,
            TotalSpaceProbe totalSpace,
            UsableSpaceProbe usableSpace) {
        this(matches, clips, settings, events, maintenanceLock, totalSpace, usableSpace, Files::size);
    }

    /** Test seam: additionally inject a deterministic per-file size probe. */
    RetentionSweeper(
            MatchRepository matches,
            ClipRepository clips,
            SettingsStore settings,
            EventPublisher events,
            StorageMaintenanceLock maintenanceLock,
            TotalSpaceProbe totalSpace,
            UsableSpaceProbe usableSpace,
            FileSizeProbe fileSize) {
        this(matches, clips, settings, events, maintenanceLock, totalSpace, usableSpace, fileSize,
                System::currentTimeMillis);
    }

    /** Test seam: additionally inject a deterministic wall clock for the miss-confirmation age gate. */
    RetentionSweeper(
            MatchRepository matches,
            ClipRepository clips,
            SettingsStore settings,
            EventPublisher events,
            StorageMaintenanceLock maintenanceLock,
            TotalSpaceProbe totalSpace,
            UsableSpaceProbe usableSpace,
            FileSizeProbe fileSize,
            MillisClock clock) {
        this.matches = matches;
        this.clips = clips;
        this.settings = settings;
        this.events = events;
        this.maintenanceLock = maintenanceLock;
        this.totalSpace = totalSpace;
        this.usableSpace = usableSpace;
        this.fileSize = fileSize;
        this.clock = clock;
    }

    /**
     * Scheduled hourly sweep with no protected match (nothing is actively recording from here). The
     * initialDelay keeps the first sweep from firing the instant the scheduler starts — before the
     * startup {@code MigrationRunner} runs — so it can't query the {@code clips}/{@code matches} tables
     * before a pending migration has created them (a fresh/upgrade boot otherwise logged a spurious
     * "no such table" error).
     */
    @Scheduled(initialDelay = 60_000L, fixedDelay = 3_600_000L)
    public void sweep() {
        try {
            sweep(null);
        } finally {
            // Surface the low-disk warning on the same cadence, AFTER the sweep so it reflects the
            // post-eviction disk state — this is what actually delivers the check's {scope:"disk"}
            // frame to the bridge WS. Cheap and non-fatal by design: a failed probe must never look
            // like a failed sweep (and a failed sweep must not suppress the warning).
            try {
                checkFreeSpaceWarning();
            } catch (RuntimeException e) {
                log.warn("Scheduled free-space check failed: {}", e.toString());
            }
        }
    }

    /**
     * Runs one retention pass. While total stored video exceeds the cap, deletes the oldest
     * non-starred recording's files and nulls its path columns, until under cap or no candidate
     * remains. The {@code protectedId} (e.g. the actively-recording match) is skipped even if it is
     * the oldest, so an in-progress .mp4 is never deleted.
     *
     * <p>Files confirmed GONE from a reachable drive (deleted outside the app) count 0 bytes from
     * the FIRST miss, so a stale DB size can never linger as phantom budget that evicts real
     * recordings to offset bytes that don't exist. The destructive row catch-up (paths nulled; a
     * clip's row dropped) additionally waits until a LATER sweep, at least 30 minutes after the
     * first miss, confirms it — see {@link #missConfirmedAcrossSweeps} — so a transiently-missing
     * file (e.g. a starred VOD mid-cut-paste to a slow drive) keeps its row intact. When any
     * reachable file's size can't be
     * measured at all, the pass measures but does NOT evict — deleting real VODs against guessed
     * bytes is worse than sweeping an hour late.
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
            sweepPass++; // one tick per pass: the miss registry tells "same pass" from "a later one"
            long capBytes = capBytes();
            StoredBytes stored = measureStoredBytes();
            long total = stored.totalBytes();
            if (total <= capBytes) {
                return SweepResult.empty(total, capBytes);
            }
            if (stored.ambiguous()) {
                // Over cap, but at least one reachable file's size is only a DB-snapshot guess (its
                // stat failed for a reason other than the file being gone). Evicting real recordings
                // against guessed bytes is worse than sweeping late: skip this cycle's eviction and
                // let the next scheduled pass retry with a clean measurement.
                log.warn("Retention sweep skipped this cycle: stored-bytes measurement was ambiguous "
                        + "({} bytes measured vs cap {})", total, capBytes);
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
                if (!driveReachable(m.videoPath())) {
                    // The file's drive is offline (e.g. an unplugged archive). We can't delete it, and
                    // deleteFileQuietly would treat the unreachable path as "already gone" (deleteIfExists
                    // returns false, exists() is false) and null the row — silently orphaning an intact
                    // VOD. Skip it; its bytes are also excluded from the budget (measureStoredBytes),
                    // so an offline archive can neither drive eviction nor be cannibalized.
                    continue;
                }
                try {
                    FileStat stat = statFileBytes(m.videoPath(), m.fileSizeBytes());
                    if (stat.ambiguous()) {
                        // A candidate whose size can't be trusted must not be deleted against the DB
                        // guess — crediting phantom bytes would end the pass early or evict extra
                        // rows. Skip it; the next sweep retries with a clean stat.
                        log.warn("Retention sweep skipped match {}: could not stat {}",
                                m.id(), m.videoPath());
                        continue;
                    }
                    if (stat.missing()) {
                        // Vanished between the budget measurement and this row's turn: nothing to
                        // unlink. Credit back whatever the measurement counted for this candidate
                        // (0 if it was already missing then), or the pass would keep evicting other
                        // VODs to offset bytes that are already gone. The row reconcile itself is
                        // deferred until a later sweep confirms the miss.
                        total -= stored.countedBytes().getOrDefault(
                                missKey("match", m.id(), m.videoPath()), 0L);
                        reconcileMissingMatchVideo(m);
                        continue;
                    }
                    long size = stat.bytes();
                    // Re-check starred ATOMICALLY at deletion time: a star PATCH commits outside the
                    // maintenance lock, so a row starred between the candidate snapshot and this turn
                    // must win. The claim's WHERE re-tests starred=0 (and that the row still points at
                    // the snapshotted file) in the same UPDATE that prunes the paths — no
                    // check-then-unlink window remains. The claim also prunes the VOD row only
                    // (markers/stats survive with nulled paths); its clips are NOT cascade-deleted:
                    // clips are standalone files, kept and evicted LAST — after every non-starred
                    // VOD — in the clip phase below.
                    if (!matches.claimForSweep(m.id(), m.videoPath())) {
                        continue; // starred (or repointed/pruned) since the snapshot — not deletable
                    }
                    boolean videoGone = deleteFileQuietly(m.videoPath());
                    boolean thumbGone = deleteFileQuietly(m.thumbPath());
                    if (!videoGone) {
                        // The file is still on disk: undo the claim so the row keeps referencing its
                        // intact VOD and the next sweep retries — never an invisibly orphaned file.
                        matches.restoreVideoPath(m.id(), m.videoPath(), m.thumbPath(), m.fileSizeBytes());
                        log.warn("Retention sweep left match {} intact because video deletion failed", m.id());
                        continue;
                    }
                    total -= size;
                    if (!thumbGone) {
                        log.warn(
                                "Retention sweep could not delete thumbnail for match {}; pruning video row anyway",
                                m.id());
                    }
                    deletedIds.add(m.id());
                    freed += size;
                } catch (RuntimeException e) {
                    // One match failing (e.g. a SQLITE_BUSY on the claim) must not abort the whole
                    // pass: log and keep evicting. A failed claim changed nothing and retries next
                    // sweep; a failed claim-RESTORE (two DB failures back to back) leaves a pruned row
                    // whose intact file needs manual re-linking — rare enough to log, not to handle.
                    log.warn("Retention sweep could not evict match {}: {}", m.id(), e.toString());
                }
            }

            // Clips last: only after exhausting non-starred VODs, evict non-starred clips oldest-first
            // until under budget. Starred clips (their own flag) are never auto-deleted. A clip has no
            // row worth keeping once its file is gone (unlike a match), so delete its row AND file.
            int clipsDeleted = 0;
            for (ClipRow clip : clips.findSweepCandidates()) {
                if (total <= capBytes) {
                    break;
                }
                if (!driveReachable(clip.videoPath())) {
                    // File on an offline drive: can't delete it. Its bytes are also excluded from the
                    // budget (measureStoredBytes), so an offline clip can neither drive eviction nor be
                    // skipped-without-subtraction — symmetric with the offline-VOD handling above.
                    continue;
                }
                try {
                    FileStat stat = statFileBytes(clip.videoPath(), clip.fileSizeBytes());
                    if (stat.ambiguous()) {
                        // Same rule as the VOD loop: never delete against a guessed size.
                        log.warn("Retention sweep skipped clip {}: could not stat {}",
                                clip.id(), clip.videoPath());
                        continue;
                    }
                    if (stat.missing()) {
                        // Vanished between the budget measurement and this clip's turn: nothing to
                        // unlink. Same credit rule as the VOD loop — refund the measurement's counted
                        // bytes — and the row drop is deferred until a later sweep confirms the miss.
                        total -= stored.countedBytes().getOrDefault(
                                missKey("clip", clip.id(), clip.videoPath()), 0L);
                        dropMissingClip(clip);
                        continue;
                    }
                    long clipSize = stat.bytes();
                    // Re-check starred ATOMICALLY at deletion time — the same guard as the VOD loop
                    // above: a star PATCH commits outside the maintenance lock, so a clip starred
                    // between the candidate snapshot and this turn must win. The claim's WHERE
                    // re-tests starred=0 (and that the row still points at the snapshotted file) in
                    // one UPDATE — no check-then-unlink window. Unlike the match claim (whose
                    // nulled paths ARE the swept row's end state), the clip claim mutates NOTHING:
                    // a clip row is deleted outright after the unlink, so a path-nulling claim
                    // would only open a crash window — a hard kill between the committed null and
                    // the unlink would leave a pathless row whose .mp4 still exists, invisible to
                    // the budget (measureStoredBytes skips null-path clip rows) and never swept.
                    if (!clips.claimForSweep(clip.id(), clip.videoPath())) {
                        continue; // starred (or repointed/deleted) since the snapshot — not deletable
                    }
                    // File next, row LAST — mirroring the VOD path's discipline. A clip's row is deleted
                    // outright (unlike a match, which keeps its row), so if we dropped the row first and the
                    // unlink then failed (locked file, or a crash between the autocommitted delete and the
                    // unlink) the .mp4 would leak permanently: no row references it, and CrashRecoveryRunner's
                    // non-recursive active-drive scan never descends into videoDir/clips/ to reclaim it. Its
                    // bytes would also drop out of the row-based measured budget, under-counting the
                    // cap while `freed` over-reported. So unlink first and gate the row-delete + accounting on
                    // the .mp4 actually being gone.
                    boolean videoGone = deleteFileQuietly(clip.videoPath());
                    boolean thumbGone = deleteFileQuietly(clip.thumbPath());
                    if (!videoGone) {
                        // The file is still on disk, and the claim mutated nothing, so the row
                        // still references its intact .mp4 as-is — nothing to undo, nothing to
                        // credit. The next sweep retries — no leak, no budget drift, never an
                        // invisibly orphaned file.
                        log.warn("Retention sweep left clip {} intact because video deletion failed", clip.id());
                        continue;
                    }
                    if (!thumbGone) {
                        log.warn(
                                "Retention sweep could not delete thumbnail for clip {}; deleting clip row anyway",
                                clip.id());
                    }
                    try {
                        clips.delete(clip.id());
                    } catch (RuntimeException e) {
                        // The .mp4 is already gone but the row delete failed (e.g. a SQLITE_BUSY).
                        // The claim mutated nothing, so the row still holds its (now dangling) path
                        // and re-enters the missing-file flow — a later sweep confirms the miss and
                        // drops it. DO credit the running total: those bytes are confirmed gone,
                        // and without the credit this pass would evict one extra clip to offset
                        // bytes that no longer exist. freed/clipsDeleted stay uncredited — the row
                        // survived, so nothing was fully evicted.
                        total -= stored.countedBytes().getOrDefault(
                                missKey("clip", clip.id(), clip.videoPath()), 0L);
                        log.warn("Retention sweep could not delete clip row {}: {}", clip.id(), e.toString());
                        continue;
                    }
                    total -= clipSize;
                    freed += clipSize;
                    clipsDeleted++;
                } catch (RuntimeException e) {
                    // One clip failing (e.g. a SQLITE_BUSY on the claim) must not abort the whole
                    // pass: log and keep evicting the remaining clips so the budget is still
                    // enforced. The claim mutates nothing, so however this clip failed its row is
                    // exactly as the pass found it and simply retries next sweep.
                    log.warn("Retention sweep could not evict clip {}: {}", clip.id(), e.toString());
                }
            }

            if (!deletedIds.isEmpty() || clipsDeleted > 0) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("freedBytes", freed);
                payload.put("deletedIds", deletedIds);
                events.publish("retention.swept", payload);
                log.info("Retention sweep freed {} bytes across {} recording(s) and {} clip(s) (cap {} bytes)",
                        freed, deletedIds.size(), clipsDeleted, capBytes);
            }
            return new SweepResult(freed, deletedIds, total, capBytes);
        } finally {
            maintenanceLock.unlock();
        }
    }

    /**
     * Free-space check, run on every scheduled sweep pass. Computes free bytes on the video
     * directory's filesystem and, if below the low-disk threshold, publishes a {@code {scope:"disk"}}
     * error frame and returns a warning. Returns null when disk is healthy. NEVER blocks recording --
     * it only warns.
     *
     * @return a warning string when low on disk, or null when healthy / unknown
     */
    public String checkFreeSpaceWarning() {
        Path videoDir = videoDir();
        long free;
        try {
            free = usableSpace.usableBytes(videoDir);
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
     * disabled entirely, letting the active drive grow unbounded. Clamping keeps the budget honest.
     *
     * <p>When a drive can't be stat'd the contribution depends on WHICH drive: the ACTIVE drive keeps
     * its raw configured cap (a not-yet-created videoDir on first run must not collapse the budget and
     * over-evict), but an ARCHIVE drive contributes ZERO — a disconnected archive holds no countable
     * files, so crediting its full cap as imaginary headroom would let the active drive grow toward an
     * unreachable global budget and fill the disk.
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
        long sumBytes = clampedCapBytes(videoDir, activeCapGb, true);
        if (archives != null) {
            for (SettingsStore.StorageLocation loc : archives) {
                if (loc != null && loc.capGb() > 0) {
                    sumBytes += clampedCapBytes(loc.path(), loc.capGb(), false);
                }
            }
        }
        return sumBytes;
    }

    /**
     * A single location's budget in bytes, clamped to the drive's physical total capacity. When the
     * location can't be measured (blank/unparseable path, or the drive is unplugged), the ACTIVE drive
     * keeps its raw configured cap but an ARCHIVE drive contributes ZERO — see {@link #capBytes()}.
     */
    private long clampedCapBytes(String dir, int capGb, boolean active) {
        long capBytes = (long) capGb * BYTES_PER_GB;
        // What an unmeasurable location contributes: the active drive stays lenient (raw cap) so a
        // first-run videoDir that doesn't exist yet can't disable eviction; an archive offers nothing.
        long unmeasured = active ? capBytes : 0L;
        if (dir == null || dir.isBlank()) {
            return unmeasured;
        }
        Path path;
        try {
            path = Path.of(dir);
        } catch (RuntimeException e) {
            return unmeasured;
        }
        try {
            long total = totalSpace.totalBytes(path);
            return Math.min(capBytes, total);
        } catch (IOException | RuntimeException e) {
            // Can't measure (drive unplugged / dir not yet created). An unplugged ARCHIVE must add no
            // headroom (else the active drive grows toward a budget it can never reach and fills up);
            // the ACTIVE drive keeps its raw cap rather than collapsing the budget and over-evicting.
            log.debug("Total-space probe failed for {} (active={}); contributing {} bytes",
                    path, active, unmeasured);
            return unmeasured;
        }
    }

    private Path videoDir() {
        String dir = settings.get().videoDir;
        return Path.of(dir != null && !dir.isBlank() ? dir : ".");
    }

    /**
     * Measures the stored-bytes budget: every reachable VOD + clip at its real on-disk size, via
     * {@link #statFileBytes} — the SAME measure the eviction loops decrement by, so the budget seed
     * and the loops can't drift when a file's real size differs from its recorded
     * {@code file_size_bytes}. A file confirmed GONE from a reachable drive counts 0 from the FIRST
     * miss (a vanished file's stale DB size can never linger as phantom budget that evicts real
     * recordings); the destructive row catch-up ({@link #reconcileMissingMatchVideo} /
     * {@link #dropMissingClip}) waits for {@link #missConfirmedAcrossSweeps}'s later, age-gated
     * confirmation. What was counted per row is
     * recorded in {@code countedBytes} so the eviction loops can refund a candidate that vanishes
     * before its turn. {@code ambiguous} is set when any reachable file's stat failed for another
     * reason — the caller must not evict against such a total.
     */
    private StoredBytes measureStoredBytes() {
        long total = 0L;
        boolean ambiguous = false;
        Map<String, Long> counted = new HashMap<>();
        Set<String> liveKeys = new HashSet<>();
        for (MatchSummary m : matches.findAll()) {
            if (m.videoPath() == null || m.videoPath().isBlank()) {
                continue;
            }
            String key = missKey("match", m.id(), m.videoPath());
            liveKeys.add(key);
            // A VOD on an offline drive (unplugged archive) is not manageable budget: it can be neither
            // moved nor deleted. Counting it would make total exceed the (now archive-excluded) budget
            // and force eviction of OTHER drives' files — or orphan this one. Exclude it, symmetric with
            // clampedCapBytes contributing 0 headroom for the same unreachable archive. Its miss entry
            // (if any) also survives untouched: an offline drive is neither presence nor a miss.
            if (!driveReachable(m.videoPath())) {
                continue;
            }
            FileStat stat = statFileBytes(m.videoPath(), m.fileSizeBytes());
            if (stat.missing()) {
                reconcileMissingMatchVideo(m);
                continue;
            }
            if (!stat.ambiguous()) {
                firstMiss.remove(key); // observed present again: reset the miss-confirmation clock
            }
            ambiguous |= stat.ambiguous();
            total += stat.bytes();
            counted.put(key, stat.bytes());
        }
        // Clips are first-class stored bytes too: their rendered .mp4s count against the same cap as
        // VODs. Only REACHABLE clips count: a clip on an unplugged drive can't be evicted (the clip
        // phase skips it), so counting its bytes would inflate the over-cap amount with unreclaimable
        // budget and force eviction of reachable VODs chasing a target only offline bytes hold. Symmetric
        // with the offline-VOD exclusion above and the 0-headroom an unreachable archive contributes.
        for (ClipRow clip : clips.findAll()) {
            if (clip.videoPath() == null || clip.videoPath().isBlank()) {
                continue;
            }
            String key = missKey("clip", clip.id(), clip.videoPath());
            liveKeys.add(key);
            if (!driveReachable(clip.videoPath())) {
                continue;
            }
            FileStat stat = statFileBytes(clip.videoPath(), clip.fileSizeBytes());
            if (stat.missing()) {
                dropMissingClip(clip);
                continue;
            }
            if (!stat.ambiguous()) {
                firstMiss.remove(key);
            }
            ambiguous |= stat.ambiguous();
            total += stat.bytes();
            counted.put(key, stat.bytes());
        }
        // Prune miss entries whose row no longer carries the snapshotted path (row deleted,
        // repointed, or already reconciled): a dead key must never pre-arm some future miss.
        firstMiss.keySet().retainAll(liveKeys);
        return new StoredBytes(total, ambiguous, counted);
    }

    /**
     * Reconciles a match whose video is gone from a reachable drive (deleted outside the app): the
     * row is caught up to the post-sweep shape — kept, with its markers/stats, paths and size
     * nulled. Applies to starred rows too: the star protects the FILE from the sweeper, but this
     * file is already gone, and an unreconciled starred row would shrink the budget forever (it is
     * never a sweep candidate). The mutation waits for {@link #missConfirmedAcrossSweeps} — a first
     * miss only arms the registry, so a transiently-missing file keeps its row. Failures are
     * non-fatal — the file already counts 0 either way.
     */
    private void reconcileMissingMatchVideo(MatchSummary m) {
        if (!missConfirmedAcrossSweeps(missKey("match", m.id(), m.videoPath()))) {
            log.warn("Match {} video {} looks gone from a reachable drive; deferring the row "
                    + "reconcile until a later sweep confirms the miss", m.id(), m.videoPath());
            return;
        }
        try {
            matches.reconcileMissingVideo(m.id(), m.videoPath());
            log.warn("Match {} video {} is gone from a reachable drive; row reconciled (paths nulled)",
                    m.id(), m.videoPath());
        } catch (RuntimeException e) {
            // A SQLITE_BUSY here must not abort the pass; the next sweep re-reconciles.
            log.warn("Could not reconcile missing video for match {}: {}", m.id(), e.toString());
        }
    }

    /**
     * Drops a clip whose .mp4 is gone from a reachable drive. A clip has no row worth keeping once
     * its file is gone (unlike a match) — the eviction phase's file-gone semantics — and a lingering
     * row would keep its stale DB size in play. Like the match reconcile, the drop waits for
     * {@link #missConfirmedAcrossSweeps} so a transiently-missing clip survives a single
     * observation. Failures are non-fatal.
     */
    private void dropMissingClip(ClipRow clip) {
        if (!missConfirmedAcrossSweeps(missKey("clip", clip.id(), clip.videoPath()))) {
            log.warn("Clip {} video {} looks gone from a reachable drive; deferring the row drop "
                    + "until a later sweep confirms the miss", clip.id(), clip.videoPath());
            return;
        }
        try {
            clips.delete(clip.id());
            log.warn("Clip {} video {} is gone from a reachable drive; row dropped",
                    clip.id(), clip.videoPath());
        } catch (RuntimeException e) {
            log.warn("Could not drop missing clip {}: {}", clip.id(), e.toString());
        }
    }

    /**
     * Records a missing-file observation for {@code key} and reports whether the miss is CONFIRMED —
     * first observed on an EARLIER sweep pass, at least {@link #MISS_CONFIRMATION_MIN_AGE_MS} of
     * wall-clock ago, and still missing now — so the destructive row mutation may run. A first
     * observation (or a re-observation within the SAME pass: the measurement and an eviction loop
     * can both see one row) only arms the registry and returns false; so does a later pass that
     * arrives before the age floor. BOTH conditions matter: sweep passes are driven not just by
     * the hourly schedule but by every archiver pass (~2 min fixedDelay), so "a later pass" alone
     * would confirm a miss in ~2-4 minutes — mid-way through exactly the scenario this registry
     * protects. Rationale for deferring at all: a transiently-missing file — say a starred VOD the
     * user cut-pasted out of videoDir and later moves back — must survive the observation window
     * with its row intact, because a nulled row can never re-link its returning file (move
     * attribution requires the recorded basename) and the next boot's orphan scan would re-adopt
     * it as a NEW unstarred row at the front of the eviction queue. Only the ROW mutation waits: a
     * missing file counts 0 bytes from the first miss, so phantom bytes never survive in the
     * budget.
     */
    private boolean missConfirmedAcrossSweeps(String key) {
        long now = clock.millis();
        FirstMiss first = firstMiss.putIfAbsent(key, new FirstMiss(sweepPass, now));
        if (first == null || first.pass() >= sweepPass
                || now - first.atMillis() < MISS_CONFIRMATION_MIN_AGE_MS) {
            return false;
        }
        firstMiss.remove(key);
        return true;
    }

    /** Registry/accounting key for one row+path snapshot; the path makes a repointed row a new key. */
    private static String missKey(String kind, long id, String path) {
        return kind + ":" + id + "|" + path;
    }

    /**
     * Whether the drive holding {@code filePath} is currently reachable — i.e. the file's parent
     * directory exists. This separates a genuinely-deleted/missing file on a PRESENT drive (parent
     * exists; safe to prune the row) from a file on an UNPLUGGED drive (parent gone; the row must be
     * preserved, never orphaned). A blank or parent-less path is treated as unreachable.
     */
    private boolean driveReachable(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        try {
            Path parent = Path.of(filePath).getParent();
            return parent != null && Files.isDirectory(parent);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Stats one stored file for budget accounting. Prefers the real on-disk size. A
     * {@link NoSuchFileException} whose parent directory still exists means the FILE is confirmed
     * gone (deleted outside the app), NOT the drive: that returns {@code missing} so the caller
     * counts 0 and reconciles the row — falling back to the stale DB size there would count phantom
     * bytes forever. Every other failure (drive vanished between the caller's reachability check and
     * this stat, permissions, I/O error) is {@code ambiguous}: the DB snapshot is returned for
     * visibility, but flagged so the sweep never deletes against a guessed size.
     */
    private FileStat statFileBytes(String path, Long dbSizeBytes) {
        if (path == null || path.isBlank()) {
            return FileStat.of(0L);
        }
        try {
            return FileStat.of(fileSize.sizeBytes(Path.of(path)));
        } catch (NoSuchFileException e) {
            if (driveReachable(path)) {
                return new FileStat(0L, true, false);
            }
            return new FileStat(dbSizeBytes != null ? dbSizeBytes : 0L, false, true);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not stat {} during retention sweep: {}", path, e.toString());
            return new FileStat(dbSizeBytes != null ? dbSizeBytes : 0L, false, true);
        }
    }

    private boolean deleteFileQuietly(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        Path target;
        try {
            target = Path.of(path);
        } catch (RuntimeException e) {
            log.warn("Could not delete unparseable path {} during retention sweep: {}", path, e.toString());
            return false;
        }
        // Containment guard: never unlink a file that sits outside every configured storage root (a
        // tampered/hand-edited video_path, a `..` escape). Use the SAME allow-list the bridge streams
        // with (StorageRoots — videoDir + archive drives + previousVideoDirs + previousArchiveDirs),
        // so a legitimately
        // archived or moved-off-of VOD is still deletable and only a genuinely misrooted file is
        // refused. Return false WITHOUT nulling the row: an orphaned misrooted file must stay visible in
        // the library (row preserved, no freed-bytes credit) rather than be silently pruned as if evicted.
        if (!StorageRoots.isUnder(target, StorageRoots.of(settings.get()))) {
            log.warn("Refusing to delete {} during retention sweep: path is outside all storage roots",
                    path);
            return false;
        }
        try {
            boolean removed = Files.deleteIfExists(target);
            if (removed) {
                log.debug("Deleted {}", path);
            }
            return !Files.exists(target);
        } catch (IOException | RuntimeException e) {
            // A locked/missing file must not abort the sweep. Return false so the row keeps its path
            // and the next sweep retries instead of forgetting a file that may still be on disk.
            log.warn("Could not delete {} during retention sweep: {}", path, e.toString());
            return false;
        }
    }

    /**
     * One miss-registry entry: the sweep pass and wall-clock millis at which a row's file was
     * FIRST observed missing. Confirmation requires a LATER pass AND
     * {@link #MISS_CONFIRMATION_MIN_AGE_MS} of elapsed wall-clock — see
     * {@link #missConfirmedAcrossSweeps}.
     */
    private record FirstMiss(long pass, long atMillis) {
    }

    /**
     * One stored file's stat outcome for budget accounting. {@code bytes} is what to count.
     * {@code missing} = the drive is reachable but the file is confirmed GONE (count 0; the caller
     * reconciles the row). {@code ambiguous} = the stat failed for any other reason, so {@code bytes}
     * is only the DB snapshot — a guess that must never drive a deletion.
     */
    private record FileStat(long bytes, boolean missing, boolean ambiguous) {
        static FileStat of(long bytes) {
            return new FileStat(bytes, false, false);
        }
    }

    /**
     * Result of {@link #measureStoredBytes}: the reachable stored total, whether any part of it is a
     * DB-snapshot guess ({@code ambiguous}) that must not drive an eviction, and the bytes counted
     * per row key so the eviction loops can refund a candidate that vanishes before its turn.
     */
    private record StoredBytes(long totalBytes, boolean ambiguous, Map<String, Long> countedBytes) {
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
