package dev.dotarec.fsm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.data.MarkerRepository;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.NewMatch;
import dev.dotarec.data.MatchSummary;
import dev.dotarec.data.PauseRepository;
import dev.dotarec.data.RecordingSessionRepository;
import dev.dotarec.data.RecordingSessionRepository.RecordingEventRow;
import dev.dotarec.data.RecordingSessionRepository.RecordingSessionRow;
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

    private final RecordingSessionRepository journal;
    private final MatchRepository matches;
    private final MarkerRepository markers;
    private final PauseRepository pauses;
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final SettingsStore settings;

    public CrashRecoveryRunner(
            RecordingSessionRepository journal,
            MatchRepository matches,
            MarkerRepository markers,
            PauseRepository pauses,
            DataSource dataSource,
            ObjectMapper mapper,
            SettingsStore settings) {
        this.journal = journal;
        this.matches = matches;
        this.markers = markers;
        this.pauses = pauses;
        this.dataSource = dataSource;
        this.mapper = mapper;
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
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
        // as a standalone gsi_only row (its OBS name carries no match id to tie it back to).
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
     *       importing a detached duplicate;</li>
     *   <li>if the match is already intact (its row still points at a file that exists), this is a
     *       redundant leftover/partial copy from the move — DELETE it to reclaim the space;</li>
     *   <li>if there is no id prefix or no such match (the row was deleted), fall back to importing it
     *       as a standalone gsi_only row, exactly as the active-drive scan does.</li>
     * </ul>
     */
    private void recoverArchiveLeftover(Path file) {
        try {
            Long matchId = leadingMatchId(file.getFileName().toString());
            if (matchId != null) {
                Optional<MatchSummary> row = matches.findById(matchId);
                if (row.isPresent()) {
                    if (videoFileMissing(row.get())) {
                        String thumb =
                                recoverArchivedThumb(file.getParent(), matchId, row.get().thumbPath());
                        matches.updateVideoPath(matchId, file.toString(), thumb);
                        log.warn("Re-linked stranded archive recording {} to match {} (interrupted move)",
                                file, matchId);
                    } else {
                        deleteQuietly(file);
                        log.warn("Removed redundant archive move-leftover {} (match {} already intact)",
                                file, matchId);
                    }
                    return;
                }
            }
            importOrphanVod(file);
        } catch (Exception e) {
            // One bad leftover must not abort the rest of the scan.
            log.warn("Could not recover archive leftover {}: {}", file, e.toString());
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

    /** True when a row's current video file is absent (null/blank path, or the file no longer exists). */
    private boolean videoFileMissing(MatchSummary row) {
        Path p = safePath(row.videoPath());
        return p == null || !Files.exists(p);
    }

    /**
     * Finds the archived thumbnail paired with a recovered video — the archiver writes it to
     * {@code <dir>/thumbs/<matchId>-<original>}. Returns {@code fallback} (the row's existing thumb) when
     * none is found, so a re-link never blanks a thumbnail it couldn't improve on.
     */
    private String recoverArchivedThumb(Path archiveDir, long matchId, String fallback) {
        if (archiveDir == null) {
            return fallback;
        }
        Path thumbDir = archiveDir.resolve("thumbs");
        if (!Files.isDirectory(thumbDir)) {
            return fallback;
        }
        String prefix = matchId + "-";
        try (Stream<Path> stream = Files.list(thumbDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
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
            long size = Files.size(path);
            if (size <= 0) {
                return;
            }
            long playedAt = Files.getLastModifiedTime(path).toMillis();
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

    private static String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    private record MarkerPayload(
            String type, double videoOffsetS, Integer gameClock, String label, String source) {}
}
