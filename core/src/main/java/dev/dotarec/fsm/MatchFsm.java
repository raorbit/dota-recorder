package dev.dotarec.fsm;

import dev.dotarec.bridge.EventPublisher;
import dev.dotarec.data.MarkerRepository;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.NewMatch;
import dev.dotarec.data.PauseRepository;
import dev.dotarec.data.RecordingSessionRepository;
import dev.dotarec.data.RecordingSessionRepository.RecordingEvent;
import dev.dotarec.data.RecordingSessionRepository.RecordingSessionRow;
import dev.dotarec.data.RecordingSessionRepository.Snapshot;
import dev.dotarec.fsm.RecordingSession.PauseSpanBuffer;
import dev.dotarec.gsi.GsiFrame;
import dev.dotarec.obs.ObsException;
import dev.dotarec.obs.ObsRecorder;
import dev.dotarec.obs.ThumbnailCapturer;
import dev.dotarec.tagger.EventTagger;
import dev.dotarec.tagger.PendingMarker;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Match state machine: interprets GSI frames and decides when to start/stop recording.
 *
 * <p>Lifecycle (plan: Match lifecycle):
 * <ul>
 *   <li>Arm + StartRecord EARLY -- at HERO_SELECTION / STRATEGY_TIME / PRE_GAME -- so OBS's
 *       StartRecord -&gt; OUTPUT_STARTED latency cannot clip the opening of the match.</li>
 *   <li>GAME_IN_PROGRESS is a valid ENTRY state: if the app launches mid-match, recording starts
 *       immediately, gated on {@code activity == "playing"} so a spectator/menu frame never records.
 *       It is also the steady tagging state once rolling.</li>
 *   <li>POST_GAME (or {@link #forceFinalize()} for the later watchdog) cuts the recording: capture
 *       thumbnail (BEFORE stop), StopRecord, then persist the {@code matches} row + buffered markers
 *       and publish {@code match.recorded}.</li>
 *   <li>Unknown / unrecognized {@code game_state} values are safe no-ops (hold current state) so the
 *       FSM survives Dota client version churn; an absent state maps to {@code "UNKNOWN"} upstream.</li>
 * </ul>
 *
 * <p>Idempotency: a recording is started exactly once per match. Re-entering an arm state, or a
 * stream of identical GAME_IN_PROGRESS frames, never issues a second StartRecord -- the guard is the
 * current {@link #state}, not the frame.
 *
 * <p>Threading: {@link #onFrame} runs on the GSI request thread (~10Hz). Tagging is cheap (an
 * in-memory diff that buffers markers). Finalize is the one heavy step (thumbnail + StopRecord + DB
 * writes); for v0.1 it runs synchronously on this thread -- acceptable because POST_GAME fires once
 * per match, but a later PR should hand finalize to a single-threaded executor so a slow OBS/disk
 * cannot stall the feed (and {@link GsiFrame}s during finalize are simply no-ops since we're already
 * past RECORDING).
 */
@Service
public class MatchFsm {

    private static final Logger log = LoggerFactory.getLogger(MatchFsm.class);

    private static final String HERO_SELECTION = "DOTA_GAMERULES_STATE_HERO_SELECTION";
    private static final String STRATEGY_TIME = "DOTA_GAMERULES_STATE_STRATEGY_TIME";
    private static final String PRE_GAME = "DOTA_GAMERULES_STATE_PRE_GAME";
    private static final String GAME_IN_PROGRESS = "DOTA_GAMERULES_STATE_GAME_IN_PROGRESS";
    private static final String POST_GAME = "DOTA_GAMERULES_STATE_POST_GAME";

    /** Live tagging has no final duration yet; pass a generous clamp bound (offsets are naturally
     * within it because elapsed-since-start can't exceed wall time). Finalize stores the real one. */
    private static final double LIVE_DURATION_CLAMP = Double.MAX_VALUE;

    private final ObsRecorder obs;
    private final ThumbnailCapturer thumbnails;
    private final EventTagger tagger;
    private final MatchRepository matches;
    private final MarkerRepository markers;
    private final PauseRepository pauses;
    private final RecordingSessionRepository journal;
    private final EventPublisher events;
    private final DataSource dataSource;

    private volatile MatchState state = MatchState.IDLE;
    private RecordingSession session;

    public MatchFsm(
            ObsRecorder obs,
            ThumbnailCapturer thumbnails,
            EventTagger tagger,
            MatchRepository matches,
            MarkerRepository markers,
            PauseRepository pauses,
            RecordingSessionRepository journal,
            EventPublisher events,
            DataSource dataSource) {
        this.obs = obs;
        this.thumbnails = thumbnails;
        this.tagger = tagger;
        this.matches = matches;
        this.markers = markers;
        this.pauses = pauses;
        this.journal = journal;
        this.events = events;
        this.dataSource = dataSource;
    }

    public MatchState getState() {
        return state;
    }

    /** The in-flight session, or null when IDLE. Exposed for tests/diagnostics. */
    public RecordingSession currentSession() {
        return session;
    }

    /**
     * Drives the FSM from a single normalized frame. Maps {@code frame.gameState} to start/tag/stop
     * side effects. Synchronized so the ~10Hz feed can never interleave a start with a finalize.
     */
    public synchronized void onFrame(GsiFrame frame) {
        if (frame == null) {
            return;
        }
        String gs = frame.gameState();

        switch (state) {
            case IDLE -> {
                if (isArmState(gs) || (GAME_IN_PROGRESS.equals(gs) && isPlaying(frame))) {
                    startRecording(frame);
                }
                // Any other state (UNKNOWN, INIT, menu activity, POST_GAME with nothing armed) is a
                // safe no-op: nothing to record, nothing to finalize.
            }
            case RECORDING -> {
                if (POST_GAME.equals(gs)) {
                    finalizeRecording();
                } else if (shouldRollToNewRecording(frame)) {
                    log.info(
                            "Detected new match while recording (state={}, match_id={}); finalizing current recording first",
                            frame.gameState(),
                            frame.matchId());
                    finalizeRecording();
                    if (state == MatchState.IDLE) {
                        onFrame(frame);
                    }
                } else {
                    tagAndObserve(frame);
                }
            }
            // ARMED/STOPPING are transient; for v0.1 we move straight IDLE<->RECORDING, so treat any
            // frame here defensively as a no-op until the state settles.
            default -> {
                // no-op
            }
        }
    }

    /**
     * Watchdog hook: force the in-flight recording to finalize as if POST_GAME arrived (e.g. the GSI
     * feed died mid-match and the heartbeat grace window expired). No-op when not recording.
     */
    public synchronized void forceFinalize() {
        if (state == MatchState.RECORDING) {
            log.info("Force-finalizing in-flight recording (watchdog)");
            finalizeRecording();
        }
    }

    private boolean isArmState(String gs) {
        return HERO_SELECTION.equals(gs) || STRATEGY_TIME.equals(gs) || PRE_GAME.equals(gs);
    }

    private boolean isPlaying(GsiFrame frame) {
        return "playing".equals(frame.activity());
    }

    private boolean shouldRollToNewRecording(GsiFrame frame) {
        RecordingSession s = this.session;
        if (s == null) {
            return false;
        }
        long currentMatchId = s.getMatchId();
        long nextMatchId = frame.matchId();
        if (currentMatchId != 0L && nextMatchId != 0L && nextMatchId != currentMatchId) {
            // A genuinely different match id while recording -> the previous match ended; roll.
            return true;
        }
        // An arm state (HERO_SELECTION/STRATEGY_TIME/PRE_GAME) only signals a NEW match once we've
        // already reached GAME_IN_PROGRESS for the current one (a fresh draft began without a
        // POST_GAME). Repeated arm-state frames of the SAME draft -- of which Dota streams many at
        // ~10Hz before the horn -- must NOT roll, or one match would be shredded into dozens of tiny
        // start/stop VOD rows.
        return isArmState(frame.gameState()) && s.hasReachedGameInProgress();
    }

    private void startRecording(GsiFrame frame) {
        if (!obs.ensureConnected()) {
            // OBS down: stay IDLE and retry on the next frame. Never throw on the GSI thread.
            log.warn("Cannot start recording: OBS not connected; will retry on next frame");
            return;
        }
        // Readiness gate: OBS connected but with no active program scene or a muted/absent
        // desktop-audio input would capture a black/silent file against a green GSI card. Stay IDLE
        // and retry rather than record nothing -- we armed early (HERO_SELECTION), so there is slack.
        if (!obs.isReady()) {
            log.warn(
                    "OBS connected but not ready to record (no active scene or muted/absent audio);"
                            + " staying IDLE, will retry on next frame");
            return;
        }
        try {
            // startRecording() blocks until OBS confirms OUTPUT_STARTED (or throws on timeout/reject),
            // so reaching the next line means a real recording is rolling.
            obs.startRecording();
        } catch (ObsException e) {
            log.warn("Recording not confirmed by OBS: {}; staying IDLE", e.getMessage());
            return;
        }

        RecordingSession s = new RecordingSession();
        s.setSurrogateId(UUID.randomUUID().toString());
        // startRecording() returned only after OUTPUT_STARTED, so recordConfirmedAt() is the fresh,
        // per-recording anchor. The wall-clock fallback is purely defensive (a seam that doesn't post
        // an instant); production always has a confirmed instant here.
        Instant confirmed = obs.recordConfirmedAt();
        long anchor = confirmed != null ? confirmed.toEpochMilli() : frame.wallClockMillis();
        s.setRecordConfirmedWallMs(anchor);
        s.setRecordStartedWallMs(anchor);
        s.observe(frame);
        s.setLastFrame(frame);
        if (GAME_IN_PROGRESS.equals(frame.gameState())) {
            s.markReachedGameInProgress();
        }
        // If recording opens mid-match while already paused (launched during a live-game pause), seed
        // the leading pause span here: tagAndObserve only detects edges from the SECOND frame on (the
        // first frame is consumed here), so a begins-paused match would otherwise drop the leading
        // span. Gate on the steady-play entry so a paused flag on an arm-state frame (hero
        // select/strategy) can't open a span before the match is actually rolling.
        if (GAME_IN_PROGRESS.equals(frame.gameState()) && isPlaying(frame) && frame.paused()) {
            s.openPause(frame.wallClockMillis());
        }
        openJournal(s, frame);
        if (GAME_IN_PROGRESS.equals(frame.gameState()) && isPlaying(frame) && frame.paused()) {
            appendJournalEvent(s, "pause_open", frame.wallClockMillis(), frame.gameClock(), null);
        }

        this.session = s;
        this.state = MatchState.RECORDING;
        log.info("Recording started (surrogate {}), anchor={}", s.getSurrogateId(), anchor);
    }

    private void tagAndObserve(GsiFrame frame) {
        RecordingSession s = this.session;
        if (s == null) {
            return;
        }
        GsiFrame last = s.getLastFrame();
        List<PendingMarker> detected =
                tagger.diff(last, frame, s.getRecordConfirmedWallMs(), LIVE_DURATION_CLAMP);
        if (!detected.isEmpty()) {
            s.addMarkers(detected);
            for (PendingMarker marker : detected) {
                appendJournalEvent(
                        s,
                        "marker",
                        frame.wallClockMillis(),
                        marker.gameClock(),
                        markerPayload(marker));
            }
        }
        // Pause edge: buffer a span open on false->true, close it on true->false. Persisted at
        // finalize once the matches row (the FK target) exists. A recording that opens mid-pause has
        // its leading span seeded in startRecording, so by here last is always non-null (set there);
        // the null check is purely defensive.
        boolean was = last != null && last.paused();
        boolean now = frame.paused();
        if (!was && now) {
            s.openPause(frame.wallClockMillis());
            appendJournalEvent(s, "pause_open", frame.wallClockMillis(), frame.gameClock(), null);
        } else if (was && !now) {
            s.closePause(frame.wallClockMillis());
            appendJournalEvent(s, "pause_close", frame.wallClockMillis(), frame.gameClock(), null);
        }
        s.observe(frame);
        s.setLastFrame(frame);
        if (GAME_IN_PROGRESS.equals(frame.gameState())) {
            s.markReachedGameInProgress();
        }
        updateJournalSnapshot(s, "recording", null, null);
    }

    private void finalizeRecording() {
        RecordingSession s = this.session;
        // Defensive: nothing to finalize -> reset to IDLE.
        if (s == null) {
            this.state = MatchState.IDLE;
            return;
        }
        this.state = MatchState.STOPPING;
        try {
            String videoPath = null;
            String thumbPath = null;
            try {
                // Thumbnail BEFORE stop: a screenshot after the scene goes idle is black.
                try {
                    Path thumb = thumbnails.captureCurrentScene(s.getSurrogateId());
                    thumbPath = thumb != null ? thumb.toString() : null;
                } catch (Exception e) {
                    // A missing thumbnail must not lose the recording; persist the row without it.
                    log.warn("Thumbnail capture failed for {}: {}", s.getSurrogateId(), e.toString());
                }

                videoPath = stopRecordingWithRetry();
            } catch (RuntimeException e) {
                // Defensive: stopRecordingWithRetry already swallows expected stop failures, but a
                // seam implementation bug must still fall through to persistence.
                log.warn("StopRecord handling failed unexpectedly: {}; persisting without video path",
                        e.toString());
            }

            long now = System.currentTimeMillis();
            long anchor = s.getRecordConfirmedWallMs();
            // Duration from the confirmed start to stop; clamp to >= 0.
            int durationS = (int) Math.max(0, (now - anchor) / 1000);
            Long fileSizeBytes = fileSizeOrNull(videoPath);
            updateJournalSnapshot(s, "stopping", videoPath, thumbPath);

            // Persist the match row + its markers + pauses in ONE transaction so a child-write
            // failure can't leave an orphan match row. publishRecorded runs only after commit, so the
            // UI never sees a match that rolled back.
            long matchRowId = persistFinalized(s, videoPath, thumbPath, fileSizeBytes, durationS, now);

            publishRecorded(matchRowId, s, durationS);

            log.info("Recording finalized -> match row {} ({} markers, {}s)",
                    matchRowId, s.getMarkers().size(), durationS);
        } catch (RuntimeException e) {
            // A persistence failure (disk full, FK/constraint violation, a publisher error) must NOT
            // strand the FSM in STOPPING: both onFrame and the watchdog gate on RECORDING, so a stuck
            // STOPPING would silently kill recording for the rest of the session. The finalize writes
            // are one transaction now, so a failure here rolls back cleanly -- no partial row -- and we
            // fall through to the reset below rather than losing all future recordings.
            log.error("Finalize failed after stopping the recording: {}", e.toString(), e);
        } finally {
            // Always return to IDLE so the next match can record, regardless of how finalize fared.
            this.session = null;
            this.state = MatchState.IDLE;
        }
    }

    /**
     * Persists the finalized match row plus its markers and pauses in a single transaction on one
     * connection, committing on success and rolling back on ANY failure -- so a marker/pause write
     * error can never leave an orphan match row with the buffered children silently dropped (the
     * three writes used to run on independent connections). SQLite sees the uncommitted parent row on
     * the same connection, so the children's foreign key resolves before commit. Returns the new
     * match row id.
     */
    private long persistFinalized(
            RecordingSession s,
            String videoPath,
            String thumbPath,
            Long fileSizeBytes,
            int durationS,
            long now) {
        NewMatch row = newMatchRow(s, videoPath, thumbPath, fileSizeBytes, durationS, now);
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long matchRowId = matches.insert(conn, row);
                for (PendingMarker m : s.getMarkers()) {
                    // Re-clamp the live offset to the now-known real duration so a marker can't sit
                    // past the end of the file (live tagging used a generous bound).
                    double offset = Math.min(m.videoOffsetS(), durationS);
                    markers.insert(conn, matchRowId, m.type(), offset, m.gameClock(), m.label(),
                            m.source());
                }
                // drainPauses closes any still-open span to finalize time so no row carries a null
                // end_wall (match ended while paused, or the watchdog force-finalized mid-pause).
                for (PauseSpanBuffer span : s.drainPauses(now)) {
                    pauses.insert(conn, matchRowId, span.startWall(), span.endWall());
                }
                journal.delete(conn, s.getSurrogateId());
                conn.commit();
                return matchRowId;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e instanceof RuntimeException re
                        ? re
                        : new IllegalStateException("Failed to persist finalized match", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist finalized match", e);
        }
    }

    private NewMatch newMatchRow(
            RecordingSession s,
            String videoPath,
            String thumbPath,
            Long fileSizeBytes,
            int durationS,
            long now) {
        Long dotaMatchId = s.getMatchId() != 0L ? s.getMatchId() : null;
        return new NewMatch(
                dotaMatchId,
                "match",
                // result/MMR/lobby/etc. are enrichment's job; live GSI can't reliably know win/loss
                // for the player without team context, so leave them null.
                "pending",
                s.getHero(),
                s.getKills(),
                s.getDeaths(),
                s.getAssists(),
                null, // gpm
                null, // xpm
                null, // net_worth
                null, // last_hits
                null, // result
                null, // lobby_type
                null, // game_mode
                null, // rank_tier
                null, // mmr_delta
                durationS,
                now, // played_at (finalize time; enrichment may correct to match start)
                videoPath,
                thumbPath,
                fileSizeBytes,
                false,
                now, // created_at
                s.getRecordStartedWallMs());
    }

    private Long fileSizeOrNull(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return Files.size(Path.of(path));
        } catch (Exception e) {
            log.warn("Could not stat finalized recording {}: {}", path, e.toString());
            return null;
        }
    }

    private String stopRecordingWithRetry() {
        RuntimeException firstFailure = null;
        try {
            String path = obs.stopRecording();
            warnIfStillRecording();
            return path;
        } catch (RuntimeException e) {
            firstFailure = e;
            log.warn("StopRecord failed: {}", e.toString());
        }

        if (!obs.isRecording()) {
            log.warn("OBS no longer reports recording after failed StopRecord; persisting without video path");
            return null;
        }

        try {
            log.warn("OBS still reports recording after failed StopRecord; retrying once");
            String path = obs.stopRecording();
            warnIfStillRecording();
            return path;
        } catch (RuntimeException e) {
            log.warn(
                    "StopRecord retry failed: {}; first failure was {}; persisting without video path",
                    e.toString(),
                    firstFailure.toString());
            warnIfStillRecording();
            return null;
        }
    }

    private void warnIfStillRecording() {
        if (obs.isRecording()) {
            log.warn("OBS still reports recording after finalize stop attempt");
        }
    }

    private void openJournal(RecordingSession s, GsiFrame frame) {
        try {
            long now = System.currentTimeMillis();
            journal.open(
                    new RecordingSessionRow(
                            s.getSurrogateId(),
                            s.getSurrogateId(),
                            "recording",
                            s.getMatchId() != 0L ? s.getMatchId() : null,
                            s.getHero(),
                            s.getRecordConfirmedWallMs(),
                            s.getRecordStartedWallMs(),
                            frame.wallClockMillis(),
                            frame.gameState(),
                            s.getKills(),
                            s.getDeaths(),
                            s.getAssists(),
                            null,
                            null,
                            now,
                            now));
        } catch (RuntimeException e) {
            log.warn("Could not open recording journal for {}: {}", s.getSurrogateId(), e.toString());
        }
    }

    private void updateJournalSnapshot(
            RecordingSession s, String journalState, String videoPath, String thumbPath) {
        GsiFrame last = s.getLastFrame();
        try {
            journal.updateSnapshot(
                    s.getSurrogateId(),
                    new Snapshot(
                            journalState,
                            s.getMatchId() != 0L ? s.getMatchId() : null,
                            s.getHero(),
                            last != null ? last.wallClockMillis() : null,
                            last != null ? last.gameState() : null,
                            s.getKills(),
                            s.getDeaths(),
                            s.getAssists(),
                            videoPath,
                            thumbPath,
                            System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.warn("Could not update recording journal for {}: {}", s.getSurrogateId(), e.toString());
        }
    }

    private void appendJournalEvent(
            RecordingSession s, String type, long wallMs, Integer gameClock, String payloadJson) {
        try {
            journal.appendEvent(
                    s.getSurrogateId(),
                    new RecordingEvent(type, wallMs, gameClock, payloadJson, System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.warn(
                    "Could not append {} event to recording journal for {}: {}",
                    type,
                    s.getSurrogateId(),
                    e.toString());
        }
    }

    private String markerPayload(PendingMarker marker) {
        return "{"
                + "\"type\":\"" + json(marker.type()) + "\","
                + "\"videoOffsetS\":" + marker.videoOffsetS() + ","
                + "\"gameClock\":" + (marker.gameClock() != null ? marker.gameClock() : "null") + ","
                + "\"label\":" + (marker.label() != null ? "\"" + json(marker.label()) + "\"" : "null") + ","
                + "\"source\":\"" + json(marker.source()) + "\""
                + "}";
    }

    private static String json(String value) {
        return value == null
                ? ""
                : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private void publishRecorded(long matchRowId, RecordingSession s, int durationS) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", matchRowId);
        payload.put("hero", s.getHero());
        payload.put("durationS", durationS);
        payload.put("markerCount", s.getMarkers().size());
        events.publish("match.recorded", payload);
    }
}
