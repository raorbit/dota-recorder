package dev.dotarec.fsm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.data.MarkerRepository;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.NewMatch;
import dev.dotarec.data.PauseRepository;
import dev.dotarec.data.RecordingSessionRepository;
import dev.dotarec.data.RecordingSessionRepository.RecordingEventRow;
import dev.dotarec.data.RecordingSessionRepository.RecordingSessionRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
        try {
            Path videoDir = videoDir();
            if (videoDir == null || !Files.isDirectory(videoDir)) {
                return;
            }
            Set<String> referenced = referencedVideoPaths();
            try (Stream<Path> stream = Files.list(videoDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(this::isRecordingFile)
                        .filter(path -> !referenced.contains(normalize(path)))
                        .forEach(this::importOrphanVod);
            }
        } catch (Exception e) {
            // Best-effort: a bad path or IO error here must never abort startup.
            log.warn("Could not reconcile orphan recordings: {}", e.toString());
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

    private boolean isRecordingFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mp4");
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
