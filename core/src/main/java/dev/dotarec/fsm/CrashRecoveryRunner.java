package dev.dotarec.fsm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.config.StorageRoots;
import dev.dotarec.data.ClipRepository;
import dev.dotarec.data.ClipRow;
import dev.dotarec.data.MarkerRepository;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.NewMatch;
import dev.dotarec.data.MatchSummary;
import dev.dotarec.data.PauseRepository;
import dev.dotarec.data.RecordingSessionRepository;
import dev.dotarec.data.RecordingSessionRepository.RecordingEventRow;
import dev.dotarec.data.RecordingSessionRepository.RecordingSessionRow;
import dev.dotarec.retention.StorageMaintenanceLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Startup reconciliation for sessions left in {@code recording_session} after a core/app crash.
 *
 * <p>This runner intentionally reconstructs only from durable journal rows/events. Full orphan VOD
 * directory scanning is a later hardening slice; here the invariant is that a journaled recording is
 * never silently forgotten.
 */
@Component
@Order(1)
public class CrashRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CrashRecoveryRunner.class);

    /** Slack on the recording-window bounds when linking a crashed recording's file by mtime. */
    private static final long LINK_WINDOW_GRACE_MS = 5L * 60_000L;

    /**
     * Quiescence window guarding orphan ADOPTION: a candidate whose last-modified time is within this
     * of now is skipped rather than imported. Recovery runs at {@code @Order(1)} concurrently with
     * Tomcat/GSI ingest and the OBS scheduler (both start at {@code finishRefresh()}), so a recording
     * can arm mid-recovery. OBS reveals the path only on StopRecord, so that live {@code .mp4} has a
     * null-path journal row and looks unreferenced — importing it would adopt the still-being-written
     * file (later duplicated by finalize and exposed to a retention delete of the shared VOD). A file a
     * live recorder is actively growing has a fresh mtime; a genuine crash orphan is stale, so skipping
     * fresh-mtime files leaves the live one for {@code MatchFsm} to finalize while still reclaiming true
     * orphans on the next boot. Self-contained (mtime only), resolution/OBS-state agnostic.
     */
    private static final long QUIESCENCE_MS = 2L * 60_000L;

    /**
     * Wall clock at construction (context refresh, before GSI ingest/OBS can arm a recording). A file
     * last modified at/after this was written by the CURRENT boot, so {@link #linkOrphanVideo} must
     * never capture it for a PRE-crash journal row — a genuine crash leftover was last written before
     * this process existed.
     */
    private final long bootWallMs = System.currentTimeMillis();

    private final RecordingSessionRepository journal;
    private final MatchRepository matches;
    private final ClipRepository clips;
    private final MarkerRepository markers;
    private final PauseRepository pauses;
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final SettingsStore settings;
    private final StorageMaintenanceLock maintenanceLock;

    public CrashRecoveryRunner(
            RecordingSessionRepository journal,
            MatchRepository matches,
            ClipRepository clips,
            MarkerRepository markers,
            PauseRepository pauses,
            DataSource dataSource,
            ObjectMapper mapper,
            SettingsStore settings,
            StorageMaintenanceLock maintenanceLock) {
        this.journal = journal;
        this.matches = matches;
        this.clips = clips;
        this.markers = markers;
        this.pauses = pauses;
        this.dataSource = dataSource;
        this.mapper = mapper;
        this.settings = settings;
        this.maintenanceLock = maintenanceLock;
    }

    @Override
    public void run(ApplicationArguments args) {
        // The archiver/sweeper @Scheduled clocks start at context refresh, but runners execute AFTER
        // refresh — a slow migration + recovery overlaps their first passes. Mid-move, the archiver's
        // fresh cross-store copy is not yet in any row, so an unlocked scan would read it as
        // unreferenced move residue and delete it just before the row is repointed at it (losing BOTH
        // copies once the archiver unlinks the source). Hold the maintenance lock across the whole
        // reconciliation, so every referenced-path snapshot below (recover's link scan and the orphan
        // scan) is taken with no move/sweep in flight and cannot go stale mid-decision.
        maintenanceLock.lock();
        try {
            List<RecordingSessionRow> unfinished = journal.findUnfinished();
            if (unfinished.isEmpty()) {
                reconcileOrphanVods();
                return;
            }
            log.warn("Recovering {} unfinished recording session(s)", unfinished.size());
            for (RecordingSessionRow session : unfinished) {
                try {
                    recover(session);
                } catch (RuntimeException e) {
                    log.error("Failed to recover recording session {}: {}", session.sessionId(), e.toString(), e);
                }
            }
            reconcileOrphanVods();
        } finally {
            maintenanceLock.unlock();
        }
    }

    private void recover(RecordingSessionRow session) {
        List<RecordingEventRow> events = journal.findEvents(session.sessionId());
        long recoveredAt = System.currentTimeMillis();
        int durationS =
                (int)
                        Math.max(
                                0,
                                (Math.max(session.updatedAt(), session.recordConfirmedWallMs())
                                                - session.recordConfirmedWallMs())
                                        / 1000);
        String videoPath = session.videoPath();
        if (videoPath == null || videoPath.isBlank()) {
            // OBS reveals the recording path only on StopRecord, so a crash mid-recording leaves the
            // journal with no path. Claim the leftover .mp4 for this recovered match rather than
            // letting the orphan scan import it as a detached, video-only row.
            videoPath = linkOrphanVideo(session);
        }
        Long fileSizeBytes = fileSizeOrNull(videoPath);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // A later successful re-record of the same Dota match may already own the (UNIQUE)
                // dota_match_id. Re-inserting would hit the constraint, roll back, and strand this
                // journal row to replay on every boot. Treat the match as already recovered and just
                // drop the stale journal row.
                if (session.dotaMatchId() != null
                        && matches.existsByDotaMatchId(conn, session.dotaMatchId())) {
                    journal.delete(conn, session.sessionId());
                    conn.commit();
                    log.warn(
                            "Recording session {} maps to already-finalized match {}; dropping stale"
                                + " journal row",
                            session.sessionId(),
                            session.dotaMatchId());
                    return;
                }

                long matchId =
                        matches.insert(
                                conn,
                                new NewMatch(
                                        session.dotaMatchId(),
                                        "match",
                                        "pending",
                                        session.hero(),
                                        session.kills(),
                                        session.deaths(),
                                        session.assists(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        durationS,
                                        session.updatedAt(),
                                        videoPath,
                                        session.thumbPath(),
                                        fileSizeBytes,
                                        false,
                                        recoveredAt,
                                        session.recordStartedWallMs()));

                recoverMarkers(conn, matchId, events, durationS);
                recoverPauses(conn, matchId, events, session.updatedAt());
                journal.delete(conn, session.sessionId());
                conn.commit();
                log.info(
                        "Recovered recording session {} into match row {} ({} events)",
                        session.sessionId(),
                        matchId,
                        events.size());
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e instanceof RuntimeException re
                        ? re
                        : new IllegalStateException("Failed to recover recording session", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to recover recording session", e);
        }
    }

    private void recoverMarkers(
            Connection conn, long matchId, List<RecordingEventRow> events, int durationS)
            throws SQLException {
        for (RecordingEventRow event : events) {
            if (!"marker".equals(event.type())) {
                continue;
            }
            MarkerPayload payload = parseMarker(event);
            if (payload == null || payload.type() == null || payload.type().isBlank()) {
                continue;
            }
            double offset = Math.min(payload.videoOffsetS(), durationS);
            markers.insert(
                    conn,
                    matchId,
                    payload.type(),
                    Math.max(0.0, offset),
                    payload.gameClock(),
                    payload.label(),
                    payload.source());
        }
    }

    private void recoverPauses(
            Connection conn, long matchId, List<RecordingEventRow> events, long fallbackEndWall)
            throws SQLException {
        Long openStart = null;
        for (RecordingEventRow event : events) {
            if ("pause_open".equals(event.type())) {
                openStart = event.wallMs();
            } else if ("pause_close".equals(event.type()) && openStart != null) {
                pauses.insert(conn, matchId, openStart, Math.max(openStart, event.wallMs()));
                openStart = null;
            }
        }
        if (openStart != null) {
            pauses.insert(conn, matchId, openStart, Math.max(openStart, fallbackEndWall));
        }
    }

    /**
     * Parses a journaled marker payload — the shared {@link MarkerPayload} shape {@code MatchFsm}
     * serializes. Deserialized field-by-field (NOT {@code readValue}) on purpose: an absent {@code
     * gameClock} falls back to the journal event's own game clock rather than becoming null, and a
     * missing {@code videoOffsetS} defaults to 0.0. Returns null on a blank or malformed payload.
     */
    private MarkerPayload parseMarker(RecordingEventRow event) {
        String payload = event.payloadJson();
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(payload);
            return new MarkerPayload(
                    text(node, "type"),
                    node.path("videoOffsetS").asDouble(0.0),
                    node.hasNonNull("gameClock") ? node.get("gameClock").asInt() : event.gameClock(),
                    text(node, "label"),
                    text(node, "source"));
        } catch (Exception e) {
            log.warn("Skipping malformed marker payload for journal event {}: {}", event.id(), e.toString());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private Long fileSizeOrNull(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return Files.size(Path.of(path));
        } catch (Exception e) {
            log.warn("Could not stat recovered recording {}: {}", path, e.toString());
            return null;
        }
    }

    private void reconcileOrphanVods() {
        Set<String> referenced = referencedVideoPaths();
        // Active drive: an unreferenced recording here is a crash that lost its journaled path; adopt it
        // as a standalone gsi_only row (its OBS name carries no match id to tie it back to). Adoption is
        // provenance-gated (recorder-produced basenames only) so a user's own videos are never claimed.
        scanOrphans(videoDir(), referenced, this::importOrphanVod);
        // Archive drives: an unreferenced recording here is the residue of an interrupted cross-store
        // move (copied across, but the row's repoint or the source delete didn't finish before a crash).
        // The destination name is {@code <matchId>-...}, so we recover the ORIGINAL row rather than
        // splitting off a duplicate — re-link it if the row lost its file, else drop the redundant copy.
        for (Path dir : archiveDirs()) {
            scanOrphans(dir, referenced, this::recoverArchiveLeftover);
        }
    }

    /**
     * Walks {@code dir} for unreferenced recording files and hands each to {@code handler}. The whole
     * walk is guarded: a missing/unplugged drive or a bad path must never abort startup, and one bad
     * drive must not stop the others from being scanned.
     */
    private void scanOrphans(Path dir, Set<String> referenced, Consumer<Path> handler) {
        try {
            if (dir == null || !Files.isDirectory(dir)) {
                return;
            }
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(this::isRecordingFile)
                        .filter(path -> !referenced.contains(normalize(path)))
                        .forEach(handler);
            }
        } catch (Exception e) {
            // Best-effort: a bad path or IO error here must never abort startup.
            log.warn("Could not reconcile orphan recordings under {}: {}", dir, e.toString());
        }
    }

    /**
     * Recovers an unreferenced recording found on an ARCHIVE drive — the residue of a cross-store move
     * a crash interrupted. The archiver names destinations {@code <matchId>-<original>}, so the leading
     * id ties the file back to its match:
     *
     * <ul>
     *   <li>if that match lost its file (the move repoint never committed, e.g. an atomic rename
     *       consumed the source before the crash), RE-LINK the row to this recovered copy instead of
     *       importing a detached duplicate — but only when the file is ATTRIBUTABLE to the row
     *       ({@link #isAttributableToLostVideo}: original basename and, when recorded, size), so a
     *       user-placed prefix collision never becomes the row's VOD;</li>
     *   <li>if the match is already intact (its row still points at a file that exists), this is a
     *       redundant leftover/partial copy from the move — DELETE it to reclaim the space;</li>
     *   <li>if there is no id prefix, no such match (the row was deleted), or the file couldn't be
     *       attributed, fall back to the provenance-gated import, exactly as the active-drive scan
     *       does.</li>
     * </ul>
     */
    private void recoverArchiveLeftover(Path file) {
        try {
            String fileName = file.getFileName().toString();
            // Clip leftovers carry a "clip-<clipId>-" prefix (a separate id space from match files); route
            // them to clip recovery so a stranded clip copy is re-linked/dropped, never adopted as a bogus
            // match. A false return (no such clip row, or a prefix collision that isn't attributable move
            // residue) falls through to the provenance-gated orphan import below.
            Long clipId = leadingClipId(fileName);
            if (clipId != null && recoverArchivedClipLeftover(file, clipId)) {
                return;
            }
            Long matchId = leadingMatchId(fileName);
            if (matchId != null) {
                Optional<MatchSummary> row = matches.findById(matchId);
                if (row.isPresent()) {
                    if (videoFileMissing(row.get().videoPath())) {
                        // A lost video is re-linked only to what is provably its own relocated copy —
                        // the same attribution the delete branch demands. A bare numeric-prefix match
                        // would let a user-placed "12-family.mp4" become match 12's VOD (and thereby
                        // retention-deletable).
                        if (isAttributableToLostVideo(file, matchId + "-", row.get())) {
                            String thumb =
                                    recoverArchivedThumb(
                                            file.getParent(), matchId + "-", row.get().thumbPath());
                            matches.updateVideoPath(matchId, file.toString(), thumb);
                            log.warn("Re-linked stranded archive recording {} to match {} (interrupted move)",
                                    file, matchId);
                            return;
                        }
                        log.warn(
                                "Archive file {} shares match {}'s id prefix but isn't attributable to"
                                    + " its missing video; not re-linking",
                                file,
                                matchId);
                        // fall through to the provenance-gated importOrphanVod below
                    } else {
                        // The row is intact, so this id-prefixed file is only redundant if it is genuinely
                        // this match's move residue — never delete a coincidental prefix collision (e.g. a
                        // user-placed "12-grand-final.mp4" against match 12). Require it to match the
                        // archiver's exact naming AND be attributable before reclaiming it; otherwise fall
                        // through to import (mirrors the clip path's policy of never destroying an
                        // unattributable file).
                        if (isGenuineMoveResidue(
                                file, matchId + "-", row.get().videoPath(), "match " + matchId)) {
                            deleteQuietly(file);
                            log.warn("Removed redundant archive move-leftover {} (match {} already intact)",
                                    file, matchId);
                            return;
                        }
                        log.warn(
                                "Archive file {} shares match {}'s id prefix but isn't attributable move"
                                    + " residue; not deleting",
                                file,
                                matchId);
                        // fall through to the provenance-gated importOrphanVod below
                    }
                }
            }
            importOrphanVod(file);
        } catch (Exception e) {
            // One bad leftover must not abort the rest of the scan.
            log.warn("Could not recover archive leftover {}: {}", file, e.toString());
        }
    }

    /**
     * True when {@code file} on an archive drive is provably an intact row's move residue, so it is
     * safe to delete. The archiver ({@link dev.dotarec.retention.RecordingArchiver}) names a relocated
     * file {@code <prefix><original file name>} ({@code <matchId>-} for VODs, {@code clip-<clipId>-}
     * for clips) and, on the cross-store copy path, verifies the copy's byte count equals the
     * source's. We mirror both:
     *
     * <ul>
     *   <li><b>naming</b> — the remainder after {@code prefix} must equal the intact row's own video
     *       file name (the {@code src.getFileName()} the archiver prefixed);</li>
     *   <li><b>attribution</b> — the leftover's on-disk size must equal the intact row's current file
     *       size (an interrupted move leaves a full-size duplicate of the same bytes).</li>
     * </ul>
     *
     * Both must hold. A coincidental id-prefix collision (a user-placed file whose name/size differs)
     * fails one of these and is imported rather than destroyed.
     */
    private boolean isGenuineMoveResidue(Path file, String prefix, String rowVideoPath, String owner) {
        String remainder = leftoverRemainder(file.getFileName().toString(), prefix);
        Path rowVideo = safePath(rowVideoPath);
        if (remainder == null || rowVideo == null) {
            return false;
        }
        // Naming: the archiver prefixed the row's own basename; anything else isn't its residue.
        if (!remainder.equals(rowVideo.getFileName().toString())) {
            return false;
        }
        // Attribution: same bytes as the intact copy (the archiver verifies copy size == source size).
        try {
            return Files.size(file) == Files.size(rowVideo);
        } catch (Exception e) {
            // Can't confirm the sizes match -> don't destroy the file; let it be imported.
            log.warn("Could not compare sizes for archive leftover {} vs {}: {}",
                    file, owner, e.toString());
            return false;
        }
    }

    /**
     * True when {@code file} on an archive drive is provably {@code row}'s own relocated recording, so
     * a row whose video went MISSING may be re-linked to it. The re-link analogue of
     * {@link #isGenuineMoveResidue}: the row's file is gone, so its stored fields stand in for it —
     *
     * <ul>
     *   <li><b>naming</b> — the remainder after the {@code <matchId>-} prefix must equal the basename
     *       of the row's recorded {@code video_path} (the {@code src.getFileName()} the archiver
     *       prefixed when it staged the move);</li>
     *   <li><b>attribution</b> — when the row recorded a file size, the leftover's on-disk size must
     *       equal it (the move never rewrites bytes).</li>
     * </ul>
     *
     * <p>A row with no recorded basename to compare (e.g. {@code video_path} already NULL) can never
     * attribute the file, so it is NOT re-linked — the provenance-gated orphan import then decides
     * whether the file is adopted as a standalone row instead.
     */
    private boolean isAttributableToLostVideo(Path file, String prefix, MatchSummary row) {
        String remainder = leftoverRemainder(file.getFileName().toString(), prefix);
        Path rowVideo = safePath(row.videoPath());
        if (remainder == null || rowVideo == null || rowVideo.getFileName() == null) {
            return false;
        }
        // Naming: the archiver prefixed the row's own basename; anything else isn't its file.
        if (!remainder.equals(rowVideo.getFileName().toString())) {
            return false;
        }
        Long storedSize = row.fileSizeBytes();
        if (storedSize == null) {
            return true; // no recorded size to compare; the basename match is the best attribution left
        }
        try {
            return Files.size(file) == storedSize;
        } catch (Exception e) {
            // Can't confirm the size matches -> don't claim the file for the row.
            log.warn("Could not stat archive leftover {} for match {}: {}", file, row.id(), e.toString());
            return false;
        }
    }

    /**
     * The part of {@code fileName} after the archiver's owner prefix ({@code <matchId>-} or
     * {@code clip-<clipId>-}), or null when the name doesn't carry exactly that prefix (a defensive
     * re-check even though the caller already parsed the id — this ties the delete decision to the
     * exact prefix string, not just a leading-digit match).
     */
    private static String leftoverRemainder(String fileName, String prefix) {
        if (!fileName.startsWith(prefix) || fileName.length() == prefix.length()) {
            return null;
        }
        return fileName.substring(prefix.length());
    }

    /**
     * Recovers an unreferenced {@code clip-<clipId>-...} file found on an archive drive — the residue of
     * an interrupted clip relocation. Mirrors {@link #recoverArchiveLeftover}'s match handling:
     *
     * <ul>
     *   <li>if the clip row lost its file (a same-filesystem rename consumed the source before the
     *       repoint committed), RE-LINK the row to this recovered copy;</li>
     *   <li>if the clip is already intact AND the file is provably its move residue (same
     *       basename/size attribution as the match path), this is a redundant copy from the
     *       interrupted move — DELETE it to reclaim the space.</li>
     * </ul>
     *
     * <p>Returns {@code false} when no such clip row exists (e.g. the clip was deleted) or when the
     * intact clip's prefix collides with a file that isn't attributable as its residue (e.g. a
     * user-placed {@code clip-12-something.mp4}), so the caller falls back to the provenance-gated
     * import rather than deleting a file it can't attribute.
     */
    private boolean recoverArchivedClipLeftover(Path file, long clipId) {
        Optional<ClipRow> row = clips.findById(clipId);
        if (row.isEmpty()) {
            return false;
        }
        if (videoFileMissing(row.get().videoPath())) {
            String thumb =
                    recoverArchivedThumb(file.getParent(), "clip-" + clipId + "-", row.get().thumbPath());
            clips.updateVideoPath(clipId, file.toString(), thumb);
            log.warn("Re-linked stranded archive clip {} to clip row {} (interrupted move)", file, clipId);
            return true;
        }
        // The row is intact, so this clip-prefixed file is only redundant if it is genuinely the
        // clip's move residue — the same attribution the match path requires before deleting.
        if (isGenuineMoveResidue(file, "clip-" + clipId + "-", row.get().videoPath(), "clip " + clipId)) {
            deleteQuietly(file);
            log.warn("Removed redundant archive clip move-leftover {} (clip {} already intact)", file, clipId);
            return true;
        }
        log.warn(
                "Archive file {} shares clip {}'s id prefix but isn't attributable move residue;"
                    + " importing rather than deleting",
                file,
                clipId);
        return false;
    }

    /**
     * Parses the clip id from the archiver's {@code clip-<clipId>-...} destination name, or null when the
     * name isn't a clip-prefixed archive file. Only called for files under archive dirs, where the
     * archiver always stamps this exact prefix on relocated clips.
     */
    private static Long leadingClipId(String fileName) {
        String prefix = "clip-";
        if (!fileName.startsWith(prefix)) {
            return null;
        }
        int dash = fileName.indexOf('-', prefix.length());
        if (dash <= prefix.length() || dash == fileName.length() - 1) {
            return null;
        }
        for (int i = prefix.length(); i < dash; i++) {
            if (!Character.isDigit(fileName.charAt(i))) {
                return null;
            }
        }
        try {
            return Long.parseLong(fileName.substring(prefix.length(), dash));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses the {@code <matchId>-...} prefix the archiver stamps onto relocated files, or null when the
     * name doesn't start with digits followed by a {@code -} and a non-empty remainder. Only called for
     * files under archive dirs (where the archiver always prefixes), so active-drive OBS names like
     * {@code 2026-06-28 14-30-15.mp4} are never run through it and the leading-year can't misfire.
     */
    private static Long leadingMatchId(String fileName) {
        int dash = fileName.indexOf('-');
        if (dash <= 0 || dash == fileName.length() - 1) {
            return null;
        }
        for (int i = 0; i < dash; i++) {
            if (!Character.isDigit(fileName.charAt(i))) {
                return null;
            }
        }
        try {
            return Long.parseLong(fileName.substring(0, dash));
        } catch (NumberFormatException e) {
            return null; // far longer than any real surrogate id -> not one of ours
        }
    }

    /** True when a stored video file is absent (null/blank path, or the file no longer exists on disk). */
    private boolean videoFileMissing(String videoPath) {
        Path p = safePath(videoPath);
        return p == null || !Files.exists(p);
    }

    /**
     * Finds the archived thumbnail paired with a recovered video — the archiver writes it to
     * {@code <dir>/thumbs/<prefix><original>} ({@code <matchId>-} for VODs, {@code clip-<clipId>-} for
     * clips). Returns {@code fallback} (the row's existing thumb) when none is found, so a re-link never
     * blanks a thumbnail it couldn't improve on.
     */
    private String recoverArchivedThumb(Path archiveDir, String prefix, String fallback) {
        if (archiveDir == null) {
            return fallback;
        }
        Path thumbDir = archiveDir.resolve("thumbs");
        if (!Files.isDirectory(thumbDir)) {
            return fallback;
        }
        try (Stream<Path> stream = Files.list(thumbDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    // Files.list is unordered; sort by name so a same-owner multi-match (a leftover from
                    // a prior interrupted move alongside the current thumb) resolves deterministically.
                    .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .findFirst()
                    .map(Path::toString)
                    .orElse(fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Deletes a leftover file, swallowing/logging IO failures so recovery never aborts on one. */
    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            log.warn("Could not delete archive move-leftover {}: {}", file, e.toString());
        }
    }

    private Set<String> referencedVideoPaths() {
        Set<String> out = new HashSet<>();
        for (var match : matches.findAll()) {
            Path p = safePath(match.videoPath());
            if (p != null) {
                out.add(normalize(p));
            }
        }
        for (var session : journal.findUnfinished()) {
            Path p = safePath(session.videoPath());
            if (p != null) {
                out.add(normalize(p));
            }
        }
        // Clips are stored .mp4s too. Active-drive originals live under videoDir/clips/ (a subdir the
        // non-recursive scan never descends into), but ARCHIVED clips sit in the archive-drive root
        // alongside match VODs. Without marking them referenced, an archived clip looks like an orphan
        // and gets adopted as a bogus gsi_only match — then becomes deletable by the age-based retention
        // sweep out from under its (possibly starred) clip row. Mark every clip's current file referenced.
        for (var clip : clips.findAll()) {
            Path p = safePath(clip.videoPath());
            if (p != null) {
                out.add(normalize(p));
            }
        }
        return out;
    }

    /**
     * The configured archive directories ({@code storageLocations[].path}). Blank entries are skipped
     * and unparseable ones map to null (the caller guards each with an {@code isDirectory} check, so
     * nulls/missing drives are tolerated). The active recording drive is scanned separately — it gets
     * the plain-import treatment, archive drives get id-prefix recovery.
     */
    private List<Path> archiveDirs() {
        List<Path> dirs = new ArrayList<>();
        List<SettingsStore.StorageLocation> archives = settings.get().storageLocations;
        if (archives != null) {
            for (SettingsStore.StorageLocation loc : archives) {
                if (loc == null || loc.path() == null || loc.path().isBlank()) {
                    continue;
                }
                dirs.add(safePath(loc.path()));
            }
        }
        return dirs;
    }

    /**
     * Finds the leftover recording for a session that crashed before its path was journaled: the
     * newest unreferenced {@code .mp4} in the video dir whose last-modified time falls within the
     * session's recording window. The recorder is single-user/sequential, so at most one in-flight
     * file exists and the match is unambiguous. Returns null if none qualifies.
     *
     * <p>Files last modified at/after {@link #bootWallMs} are excluded: recovery runs concurrently
     * with GSI ingest/OBS, so when the crash was within the ±5-min window a recording armed by the
     * CURRENT boot (path unknown until StopRecord, so unreferenced) could otherwise be captured and
     * attached to the old journal, stealing the live file from {@code MatchFsm}.
     */
    private String linkOrphanVideo(RecordingSessionRow session) {
        Path videoDir = videoDir();
        if (videoDir == null || !Files.isDirectory(videoDir)) {
            return null;
        }
        Set<String> referenced = referencedVideoPaths();
        long fromMs = session.recordStartedWallMs() - LINK_WINDOW_GRACE_MS;
        long toMs = session.updatedAt() + LINK_WINDOW_GRACE_MS;
        try (Stream<Path> stream = Files.list(videoDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(this::isRecordingFile)
                    .filter(path -> !referenced.contains(normalize(path)))
                    .filter(path -> withinWindow(path, fromMs, toMs))
                    .filter(path -> lastModifiedMs(path) < bootWallMs)
                    .max(java.util.Comparator.comparingLong(CrashRecoveryRunner::lastModifiedMs))
                    .map(Path::toString)
                    .orElse(null);
        } catch (Exception e) {
            log.warn(
                    "Could not link orphan recording for session {}: {}",
                    session.sessionId(),
                    e.toString());
            return null;
        }
    }

    private static boolean withinWindow(Path path, long fromMs, long toMs) {
        long m = lastModifiedMs(path);
        return m >= fromMs && m <= toMs;
    }

    private static long lastModifiedMs(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    private void importOrphanVod(Path path) {
        try {
            // Provenance gate: adopt only basenames this recorder writes. videoDir/archive roots are
            // user-configurable, so an arbitrary-named unreferenced .mp4 here may simply be the user's
            // own video — importing it as a match row would hand it to retention's oldest-first delete.
            // Leave anything foreign completely untouched.
            if (!hasRecorderProvenance(path)) {
                log.debug("Leaving unreferenced file {} untouched (not a recorder-produced name)", path);
                return;
            }
            long size = Files.size(path);
            if (size <= 0) {
                return;
            }
            long playedAt = Files.getLastModifiedTime(path).toMillis();
            // Quiescence guard: a recording that armed during recovery is a live .mp4 OBS is still
            // writing (its journal row has a null path, so it looks unreferenced). Adopting it would
            // steal the in-flight file from MatchFsm. A live file has a fresh mtime; skip it and let the
            // next boot reclaim it if it truly turns out to be an orphan.
            if (System.currentTimeMillis() - playedAt < QUIESCENCE_MS) {
                log.warn(
                        "Skipping orphan recording {} as too-recent (mtime within {}ms; may be a live"
                            + " recording)",
                        path,
                        QUIESCENCE_MS);
                return;
            }
            long id =
                    matches.insert(
                            new NewMatch(
                                    null,
                                    "match",
                                    "gsi_only",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    playedAt,
                                    path.toString(),
                                    null,
                                    size,
                                    false,
                                    System.currentTimeMillis(),
                                    null));
            log.warn("Imported orphan recording {} as match row {}", path, id);
        } catch (Exception e) {
            log.warn("Could not import orphan recording {}: {}", path, e.toString());
        }
    }

    /**
     * The recording-container extensions the orphan scan recognizes. The {@code RecFormat2} setting is
     * one of hybrid_mp4 / fragmented_mp4 / mkv / mov -- the first two write {@code .mp4}, so this set
     * covers all four values. A static allow-list (rather than deriving from the current
     * {@code settings.format}) is deliberate: the format setting may have changed since a crashed
     * recording was written, and the scan must still adopt files of any historical container.
     */
    private static final Set<String> RECORDING_EXTENSIONS = Set.of(".mp4", ".mkv", ".mov");

    /**
     * Basenames this recorder actually writes — the ONLY shapes the orphan scan may adopt as new
     * rows. OBS records under its default {@code FilenameFormatting}
     * ({@code %CCYY-%MM-%DD %hh-%mm-%ss}, plus the {@code " (N)"} suffix OBS appends on a name
     * collision; the written profile never overrides it) with a {@link #RECORDING_EXTENSIONS}
     * container; the archiver prefixes relocated copies with {@code <matchId>-}; and the clip
     * renderer writes {@code <matchId>-clip-<clipId>.mp4} (archived as
     * {@code clip-<clipId>-<matchId>-clip-<clipId>.mp4}). Anything else is a foreign file: adopting
     * it would create a row whose "VOD" is the user's own video, which retention would then delete
     * oldest-first — so foreign names are never imported (and never deleted), only debug-logged.
     */
    private static final Pattern RECORDER_BASENAME =
            Pattern.compile(
                    "(?:\\d+-)?\\d{4}-\\d{2}-\\d{2} \\d{2}-\\d{2}-\\d{2}(?: \\(\\d+\\))?\\.(?:mp4|mkv|mov)"
                            + "|(?:clip-\\d+-)?\\d+-clip-\\d+\\.mp4");

    /** True when {@code path}'s basename is a name this recorder (OBS, archiver, or clipper) produces. */
    private static boolean hasRecorderProvenance(Path path) {
        return RECORDER_BASENAME.matcher(path.getFileName().toString()).matches();
    }

    private boolean isRecordingFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : RECORDING_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private Path videoDir() {
        String dir = settings.get().videoDir;
        if (dir == null || dir.isBlank()) {
            return null;
        }
        return safePath(dir);
    }

    /**
     * Parses a stored path string, returning {@code null} (rather than throwing) for blank or
     * unparseable values. A corrupt/hand-edited {@code video_path} must not throw
     * {@link java.nio.file.InvalidPathException} out of this boot-time runner and crash startup.
     */
    private static Path safePath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value);
        } catch (RuntimeException e) {
            log.warn("Skipping unparseable recording path {}: {}", value, e.toString());
            return null;
        }
    }

    /** Delegates to the shared canonical form so attribution can never diverge from the archiver's. */
    private static String normalize(Path path) {
        return StorageRoots.normalize(path);
    }
}
