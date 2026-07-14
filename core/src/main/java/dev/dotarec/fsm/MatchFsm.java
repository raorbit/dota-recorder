package dev.dotarec.fsm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.bridge.EventPublisher;
import dev.dotarec.clip.ClipService;
import dev.dotarec.clip.RampageDetector;
import dev.dotarec.clip.RampageDetector.RampageSpan;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.config.TimeSource;
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
 * <p>Lifecycle:
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
    private final RecordStatusProbe statusProbe;
    private final ThumbnailCapturer thumbnails;
    private final EventTagger tagger;
    private final MatchRepository matches;
    private final MarkerRepository markers;
    private final PauseRepository pauses;
    private final RecordingSessionRepository journal;
    private final EventPublisher events;
    private final DataSource dataSource;
    private final ClipService clipService;
    private final SettingsStore settings;

    /** Serializes the journaled marker payload. Spring Boot autoconfigures the singleton; the wire shape
     * is the shared {@link MarkerPayload} record, whose component names are the frozen JSON keys
     * CrashRecoveryRunner.parseMarker reads back. */
    private final ObjectMapper mapper;

    /** The monotonic + wall clocks. The monotonic clock ({@code System::nanoTime}) anchors the
     * record-confirmed offset and derives the finalize duration; the wall clock
     * ({@code System::currentTimeMillis}) is for storage/display stamps only ({@code played_at},
     * {@code created_at}, journal, pause drain), NEVER for offset math. A test injects a fake TimeSource
     * to pin the anchor to its synthetic frames or step the wall clock backward independently. */
    private final TimeSource time;

    /** Bounded orphaned-stop retries: one per {@code ForceStopWatchdog} orphan tick, then give up loudly. */
    static final int ORPHAN_STOP_MAX_ATTEMPTS = 10;

    /**
     * Slack for the orphaned-stop duration floor. Our abandoned output never stopped, so between
     * observations its OBS-reported duration must have grown by about the MONOTONIC time elapsed:
     * each retry tick demands {@code durationMs >= lastKnownDurationMs + elapsedMs - SLACK}. The
     * ratchet alone ({@code durationMs >= lastKnownDurationMs}) is vacuous off a near-zero baseline
     * (a stop that failed seconds after OUTPUT_STARTED): ANY active output passes it, including a
     * recording the user hand-started in the managed OBS window after the wedged output ended on
     * its own -- which the retry would then cut and mis-attach. The slack is kept TIGHT -- just the
     * probe timeout (1s), scheduler jitter, and the second-granularity finalize baseline -- because
     * the two failure directions are asymmetric: slack too small falsely cancels and the file is
     * simply adopted at next boot, while slack too large lets a hand-started recording within
     * {@code SLACK - baseline} of the last observation pass the floor and be cut. Every widening
     * of this constant widens that mis-attach window one-for-one.
     */
    static final long ORPHAN_STOP_FLOOR_SLACK_MS = 5_000;

    private volatile MatchState state = MatchState.IDLE;
    private RecordingSession session;

    /**
     * Ownership memento for a stop-failed output: set when finalize gives up on a double StopRecord
     * failure while OBS still reports the output active, cleared when the output is stopped / goes
     * inactive / can no longer be attributed to us, or when a new match arms. Guarded by the FSM
     * monitor like {@link #session}. In-memory only: an app quit loses it, and the next boot's orphan
     * scan adopts the file instead.
     */
    private OrphanedStop orphanedStop;

    /** Latches the once-per-demo-session "not recording" log so the ~10Hz feed can't spam it. */
    private boolean demoSkipLogged;

    // Single constructor: Spring auto-selects it with no @Autowired needed, and the clocks arrive as one
    // injected TimeSource bean (ClockConfig) instead of per-clock test-seam ctor overloads. Those
    // overloads previously forced an @Autowired on the production ctor whose absence only broke boot
    // under @EnableScheduling (the production default) — a trap the scheduling-disabled smoke test
    // masked. A test constructs `new TimeSource(fakeNano, fakeWall)` directly to pin either clock.
    public MatchFsm(
            ObsRecorder obs,
            RecordStatusProbe statusProbe,
            ThumbnailCapturer thumbnails,
            EventTagger tagger,
            MatchRepository matches,
            MarkerRepository markers,
            PauseRepository pauses,
            RecordingSessionRepository journal,
            EventPublisher events,
            DataSource dataSource,
            ClipService clipService,
            SettingsStore settings,
            ObjectMapper mapper,
            TimeSource time) {
        this.obs = obs;
        this.statusProbe = statusProbe;
        this.thumbnails = thumbnails;
        this.tagger = tagger;
        this.matches = matches;
        this.markers = markers;
        this.pauses = pauses;
        this.journal = journal;
        this.events = events;
        this.dataSource = dataSource;
        this.clipService = clipService;
        this.settings = settings;
        this.mapper = mapper;
        this.time = time;
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
                if (demoBlocked(frame)) {
                    // Hero Demo with demo recording off: never arm. Evaluated for EVERY idle frame
                    // (not just start-worthy ones) so the log latch resets on the first non-demo
                    // frame and the next demo session logs its skip again.
                    return;
                }
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

    /**
     * Stops an OBS output the FSM no longer tracks: {@code state != RECORDING} yet OBS still reports
     * an active record output. Reachable when a finalize's StopRecord failed twice
     * ({@link #stopRecordingWithRetry} gives up and the FSM resets to IDLE while OBS keeps writing).
     * In that state the status card still shows "Recording" (it renders {@code ObsHealth.recording}),
     * but {@link #forceFinalize()} is a no-op — without this, the stop button the user sees cannot
     * actually stop OBS; only a new match's corrective StopRecord or quitting the app would.
     *
     * <p>{@code synchronized} so it cannot interleave with a concurrent arm: without the monitor, a
     * hero-select arm landing between the caller's state check and the StopRecord would get its fresh
     * recording killed. Deliberately user-initiated only (never scheduled): OBS reporting an active
     * output while the FSM is idle is also what a recording started by hand in the managed OBS window
     * looks like, and an automatic sweep would keep killing it. (The scheduled
     * {@link #retryOrphanedStop()} path is different: it only ever touches an output it can
     * positively attribute to its own abandoned stop.)
     *
     * <p>The stopped file is intentionally not imported here — the next boot's orphan scan
     * ({@link CrashRecoveryRunner}) adopts it with the usual quiescence guard.
     *
     * @return true when an orphaned output was confirmed stopped
     */
    public synchronized boolean stopOrphanedRecording() {
        if (state == MatchState.RECORDING) {
            return false;
        }
        if (!obs.isRecording()) {
            // No active output left to stop; any retained stop-retry state is moot too.
            this.orphanedStop = null;
            return false;
        }
        try {
            String path = obs.stopRecording();
            // The user-initiated stop supersedes the bounded automatic retries.
            this.orphanedStop = null;
            log.warn("Stopped FSM-orphaned OBS recording {}; the orphan scan adopts it on next boot", path);
            return true;
        } catch (RuntimeException e) {
            // Couldn't confirm the stop: leave ObsHealth.recording as-is so the UI keeps showing the
            // truth and another click (or the next match's corrective StopRecord) can retry. Retained
            // retry state stays too -- the output is still plausibly ours and the scheduled retries
            // remain bounded.
            log.warn("Could not stop orphaned OBS recording: {}", e.toString());
            return false;
        }
    }

    private boolean isArmState(String gs) {
        return HERO_SELECTION.equals(gs) || STRATEGY_TIME.equals(gs) || PRE_GAME.equals(gs);
    }

    /**
     * True when this frame belongs to a Hero Demo session and {@code recordDemoMatches} (off by
     * default) is disabled -- the IDLE gate then skips arming entirely. The setting is read per
     * frame, so toggling it in Settings takes effect on the next frame without a restart. A demo
     * already recording (started while the setting was on) is untouched: the gate only guards the
     * start, and the demo's POST_GAME / watchdog finalizes it normally.
     */
    private boolean demoBlocked(GsiFrame frame) {
        if (!frame.isHeroDemo() || settings.get().recordDemoMatches) {
            demoSkipLogged = false;
            return false;
        }
        if (!demoSkipLogged) {
            demoSkipLogged = true;
            log.info("Hero Demo detected; not recording (demo recording is off in Settings)");
        }
        return true;
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
        // A demo<->non-demo flip mid-recording is a session change the other two guards can be blind
        // to: a Hero Demo has match id 0 (so the id guard can't fire against it), and a session
        // entered straight at GAME_IN_PROGRESS skips the arm states. Keyed on the demo flag (map
        // "hero_demo_main"), NOT a generic map-name diff -- the demo map name is the only one a live
        // capture proves stable, whereas rolling on any name change would shred one real match into
        // multiple rows if the client ever churns the name across phases. A null mapName
        // (heartbeat/menu frames) carries no identity and can never flip this.
        if (frame.mapName() != null
                && s.hasMapIdentity()
                && frame.isHeroDemo() != s.isHeroDemo()) {
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
        if (orphanedStop != null) {
            // A new match is arming: it owns the output path now. obs.startRecording()'s corrective
            // StopRecord (fired while health.recording is still true) supersedes the bounded orphan
            // retries, so cancel them BEFORE that stop -- both run under this monitor, so a retry tick
            // can never race the arm for the same output.
            log.info(
                    "New match arming; cancelling orphaned-stop retries for match row {}",
                    orphanedStop.matchRowId);
            this.orphanedStop = null;
        }
        // Mark the start in-flight BEFORE the blocking OBS call so the recording gate consumed by the
        // app's auto-update install guard (GET /status -> fsm.state) sees a non-IDLE state during the
        // StartRecord -> OUTPUT_STARTED window. Otherwise state stays IDLE (and obs.recording stays
        // false) for up to the OBS start timeout, and an install approved in that window could tear OBS
        // down mid-arm and lose the opening of the match. Reset to IDLE on a failed start so onFrame
        // retries on the next frame (onFrame's default case no-ops on ARMED, so it must not linger).
        this.state = MatchState.ARMED;
        long confirmedNanos;
        try {
            // startRecording() blocks until OBS confirms OUTPUT_STARTED (or throws on timeout/reject),
            // so reaching the next line means a real recording is rolling.
            obs.startRecording();
            // Monotonic anchor stamped the instant OUTPUT_STARTED is confirmed -- the same clock the
            // GsiController stamps each frame's monotonicNanos with, so the per-marker delta is
            // immune to an OS/NTP wall-clock step (the wall anchor below is for storage/display only).
            confirmedNanos = time.nanoTime();
        } catch (ObsException e) {
            this.state = MatchState.IDLE;
            log.warn("Recording not confirmed by OBS: {}; staying IDLE", e.getMessage());
            return;
        } catch (RuntimeException e) {
            // Any OTHER unchecked failure from the OBS client (e.g. the obs-websocket library
            // rethrowing a thread interrupt as a bare RuntimeException) must ALSO un-arm the FSM.
            // Since we now flip to ARMED before this blocking call, a throw that escaped would strand
            // the FSM in ARMED forever -- onFrame no-ops on ARMED, so recording silently dies for the
            // whole session AND the auto-update recording gate reads busy forever. Reset to IDLE and
            // retry on the next frame, mirroring the stop path's RuntimeException tolerance.
            this.state = MatchState.IDLE;
            log.warn("Unexpected error starting recording; staying IDLE", e);
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
        s.setRecordConfirmedNanos(confirmedNanos);
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
        // Pass the session's persistent tagger state so death detection survives a counter/alive desync
        // across adjacent ticks and a single-frame player-block dropout on the death tick (the tagger is
        // no longer a pure prev->curr diff for deaths -- see EventTagger).
        List<PendingMarker> detected =
                tagger.diff(
                        last,
                        frame,
                        s.getTaggerState(),
                        s.getRecordConfirmedNanos(),
                        LIVE_DURATION_CLAMP);
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

            long now = time.wallMillis();
            // Finalize duration MUST come from the MONOTONIC clock, the same anchor the marker offsets
            // are measured against ({@code getRecordConfirmedNanos} / VideoOffsetCalculator) -- NOT the
            // wall clock. durationS is the clamp upper bound for every marker (persistFinalized +
            // maybeAutoClipRampages) and the stored duration_s, so if it were wall-derived a backward
            // NTP/clock step during the match would shrink it and clamp late markers to the wrong seek
            // point while the offsets themselves stayed monotonic. The wall stamps below (played_at,
            // recordStartedWallMs) are storage/display only.
            int durationS =
                    (int) Math.max(0, (time.nanoTime() - s.getRecordConfirmedNanos()) / 1_000_000_000L);
            Long fileSizeBytes = fileSizeOrNull(videoPath);
            updateJournalSnapshot(s, "stopping", videoPath, thumbPath);

            // Persist the match row + its markers + pauses in ONE transaction so a child-write
            // failure can't leave an orphan match row. publishRecorded runs only after commit, so the
            // UI never sees a match that rolled back.
            long matchRowId = persistFinalized(s, videoPath, thumbPath, fileSizeBytes, durationS, now);

            if (videoPath == null) {
                retainOrphanedStop(matchRowId, thumbPath, durationS, now);
            }

            publishRecorded(matchRowId, s, durationS);

            // Auto-clip rampages off the just-persisted markers. Fully guarded: createAuto dispatches
            // @Async so this stays cheap on the GSI thread, and any failure here must never break a
            // finalize that already committed (the match row + markers are safely persisted above).
            maybeAutoClipRampages(matchRowId, s, durationS);

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
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Long dotaMatchId = s.getMatchId() != 0L ? s.getMatchId() : null;
                // dota_match_id is UNIQUE. If this match id was already persisted -- a re-record after
                // a mid-match roll, an abandon+rejoin, or a manual/watchdog stop of a re-entered match
                // -- inserting it again throws SQLITE_CONSTRAINT_UNIQUE and rolls back the WHOLE
                // finalize, so the row + buffered markers are lost and the .mp4 is orphaned with no
                // library entry. Preserve the recording instead by saving it WITHOUT the match-id link
                // (a standalone, un-enrichable row under Unsorted): a duplicate entry beats silent loss.
                if (dotaMatchId != null && matches.existsByDotaMatchId(conn, dotaMatchId)) {
                    log.warn("Match {} already recorded; saving this recording without the "
                            + "dota_match_id link to avoid losing it", dotaMatchId);
                    dotaMatchId = null;
                }
                NewMatch row =
                        newMatchRow(s, dotaMatchId, videoPath, thumbPath, fileSizeBytes, durationS, now);
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

    /**
     * After a finalize commits, optionally carve a highlight clip around each detected rampage. Gated
     * on {@code autoClipOnRampage}; the kill offsets come straight from the just-persisted in-memory
     * markers (the same {@code videoOffsetS} base {@link ClipService} clamps), so no DB re-read. Each
     * span's bounds are padded by {@code clipPaddingSeconds} and clamped to {@code [0, durationS]}, then
     * handed to {@link ClipService#createAuto} (which dispatches @Async). Wrapped whole in try/catch: a
     * clip failure must never strand the FSM or undo a committed match.
     */
    private void maybeAutoClipRampages(long matchRowId, RecordingSession s, int durationS) {
        try {
            if (!settings.get().autoClipOnRampage) {
                return;
            }
            List<Double> killOffsets = new java.util.ArrayList<>();
            for (PendingMarker m : s.getMarkers()) {
                if ("kill".equals(m.type())) {
                    killOffsets.add(m.videoOffsetS());
                }
            }
            List<RampageSpan> spans = RampageDetector.detectFromOffsets(
                    killOffsets, RampageDetector.DEFAULT_THRESHOLD, RampageDetector.DEFAULT_MAX_GAP_SECONDS);
            if (spans.isEmpty()) {
                return;
            }
            int pad = settings.get().clipPaddingSeconds;
            for (RampageSpan span : spans) {
                double startS = clamp(span.firstOffsetS() - pad, 0.0, durationS);
                double endS = clamp(span.lastOffsetS() + pad, 0.0, durationS);
                clipService.createAuto(matchRowId, startS, endS, "rampage");
            }
            log.info("Auto-clipped {} rampage span(s) for match row {}", spans.size(), matchRowId);
        } catch (RuntimeException e) {
            // A clip-trigger failure (bad span, ClipService reject) must not undo a committed finalize.
            log.warn("Rampage auto-clip failed for match row {}: {}", matchRowId, e.toString());
        }
    }

    private static double clamp(double value, double lower, double upper) {
        return Math.max(lower, Math.min(value, upper));
    }

    private NewMatch newMatchRow(
            RecordingSession s,
            Long dotaMatchId,
            String videoPath,
            String thumbPath,
            Long fileSizeBytes,
            int durationS,
            long now) {
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

    /**
     * Retains ownership of a stop-failed output after finalize persisted its video-less row: the
     * double StopRecord failed and OBS still reports the output active, so the file OBS keeps
     * writing is OURS but unattached. Without this the reset to IDLE discards that knowledge --
     * the RECORDING-gated watchdog disarms and a tray-hidden user gets unbounded disk fill with
     * zero notification. The retained state fuels the bounded {@link #retryOrphanedStop()} ticks,
     * which can still cut the output and attach its file to the persisted row.
     */
    private void retainOrphanedStop(long matchRowId, String thumbPath, int durationS, long now) {
        if (!obs.isRecording()) {
            // The stop "failed" but the output ended anyway (or OBS is gone): nothing left to own.
            return;
        }
        // The duration baseline (durationS) was derived from time.nanoTime() moments ago in
        // finalize, so stamping the observation instant here skews the floor by at most the
        // persist round-trip -- well inside ORPHAN_STOP_FLOOR_SLACK_MS.
        this.orphanedStop =
                new OrphanedStop(matchRowId, thumbPath, durationS * 1_000L, now, time.nanoTime());
        log.warn(
                "StopRecord gave up but OBS still reports the output active; retaining orphan "
                        + "ownership of match row {} for bounded stop retries",
                matchRowId);
    }

    /**
     * One bounded retry tick for a stop-failed output the FSM still owns (see
     * {@link #retainOrphanedStop}). Driven by {@code ForceStopWatchdog} on the scheduler thread --
     * never the GSI request path. Each tick re-checks that the active output is still plausibly
     * OURS: active, with an OBS-reported duration at or beyond our last observation (a reset below
     * it means a FRESH output, e.g. one hand-started in the managed OBS window, which an automatic
     * stop must never touch -- the same objection that rejected a generic scheduled kill) AND at
     * or beyond the elapsed-time floor {@code lastKnownDurationMs + monotonicElapsed - slack} (our
     * output never stopped, so its duration must track elapsed time; a duration far short of that
     * is a fresh output even when a near-zero baseline lets it clear the ratchet -- see
     * {@link #ORPHAN_STOP_FLOOR_SLACK_MS}). Only then is another StopRecord issued. A success that reports the output path attaches the file
     * to the retained row (full recovery); on an inactive output, an unattributable output, or
     * exhausted attempts the state is cleared and the boot orphan scan stays the fallback --
     * {@code ObsHealth.recording} keeps telling the renderer the truth either way, so the manual
     * stop affordance survives a give-up.
     */
    public synchronized void retryOrphanedStop() {
        OrphanedStop o = this.orphanedStop;
        if (o == null) {
            return;
        }
        if (state != MatchState.IDLE) {
            // A new match owns the output path now (startRecording clears the state before its
            // corrective stop, so this is purely defensive).
            this.orphanedStop = null;
            return;
        }
        o.attempts++;
        RecordOutputStatus status = statusProbe.probeRecordStatus();
        if (status == null || (status.active() && status.durationMs() == null)) {
            // OBS unanswerable (or answered without a duration): the output cannot be attributed
            // this tick, so stop NOTHING -- burn the attempt and let the next tick look again.
            log.warn(
                    "Orphaned-stop retry {}/{} for match row {}: OBS record status unavailable; skipping",
                    o.attempts, ORPHAN_STOP_MAX_ATTEMPTS, o.matchRowId);
            giveUpIfExhausted(o);
            return;
        }
        if (!status.active()) {
            // The output ended without us (user stopped it in the OBS window, OBS quit): nothing
            // left to stop, and the boot orphan scan adopts the file.
            log.info(
                    "Orphaned output for match row {} is no longer active; ending stop retries",
                    o.matchRowId);
            this.orphanedStop = null;
            return;
        }
        if (status.durationMs() < o.lastKnownDurationMs) {
            // Duration reset below our last observation: this is a fresh output, not the one
            // finalize abandoned. Never touch an output we cannot attribute to ourselves.
            log.warn(
                    "Active OBS output is not the one finalize abandoned (duration {}ms < {}ms); "
                            + "ending stop retries for match row {}",
                    status.durationMs(), o.lastKnownDurationMs, o.matchRowId);
            this.orphanedStop = null;
            return;
        }
        long nowNanos = time.nanoTime();
        long elapsedMs = (nowNanos - o.lastObservationNanos) / 1_000_000L;
        long floorMs = o.lastKnownDurationMs + elapsedMs - ORPHAN_STOP_FLOOR_SLACK_MS;
        if (status.durationMs() < floorMs) {
            // Below the elapsed-time floor: our output ran continuously since the last observation,
            // so its duration must have grown by about the monotonic elapsed time. This one didn't
            // -- it is a fresh output (the wedged one ended between ticks and, e.g., a hand-started
            // recording took its place) that a near-zero baseline let past the ratchet. Same verdict
            // as the ratchet reset: cancel, touch nothing.
            log.warn(
                    "Active OBS output is not the one finalize abandoned (duration {}ms is below "
                            + "the elapsed-time floor {}ms); ending stop retries for match row {}",
                    status.durationMs(), floorMs, o.matchRowId);
            this.orphanedStop = null;
            return;
        }
        o.lastKnownDurationMs = status.durationMs();
        o.lastObservationNanos = nowNanos;
        try {
            String path = obs.stopRecording();
            this.orphanedStop = null;
            if (path != null && !path.isBlank()) {
                attachRecoveredVideo(o, path);
            } else {
                log.warn(
                        "Late StopRecord succeeded for match row {} but reported no output path; "
                                + "the boot orphan scan adopts the file",
                        o.matchRowId);
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Orphaned-stop retry {}/{} failed for match row {}: {}",
                    o.attempts, ORPHAN_STOP_MAX_ATTEMPTS, o.matchRowId, e.toString());
            giveUpIfExhausted(o);
        }
    }

    private void giveUpIfExhausted(OrphanedStop o) {
        if (o.attempts < ORPHAN_STOP_MAX_ATTEMPTS) {
            return;
        }
        // Loud terminal failure: OBS keeps writing and automation is out of safe moves. The status
        // card still renders the truthful ObsHealth.recording, so the user's stop button (or the
        // next match's corrective StopRecord, or quitting the app) remains the way out.
        log.error(
                "Giving up on the orphaned OBS output for match row {} after {} stop attempts "
                        + "(finalize abandoned it at {}); OBS is still writing -- stop it from the "
                        + "app's stop button or the OBS window",
                o.matchRowId, o.attempts, Instant.ofEpochMilli(o.abandonedAtWallMs));
        this.orphanedStop = null;
    }

    /**
     * Attaches a late-stopped file to the row finalize already persisted (markers/metadata and the
     * thumbnail captured before the failed stop are all there) -- full recovery without waiting for
     * next-boot orphan adoption. Best-effort: on any failure the file stays on disk untouched and
     * the boot scan remains the fallback.
     */
    private void attachRecoveredVideo(OrphanedStop o, String path) {
        try {
            int updated = matches.updateVideoPath(o.matchRowId, path, o.thumbPath);
            if (updated == 0) {
                log.warn(
                        "Late-stopped video {} could not be attached: match row {} is gone; "
                                + "the boot orphan scan adopts the file",
                        path, o.matchRowId);
                return;
            }
            log.info("Late StopRecord recovered {}; attached to match row {}", path, o.matchRowId);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not attach late-stopped video {} to match row {}: {}; the boot orphan "
                            + "scan adopts the file",
                    path, o.matchRowId, e.toString());
        }
    }

    /** Whether a stop-failed output's ownership is currently retained. Test/diagnostic hook. */
    synchronized boolean hasOrphanedStopRetry() {
        return orphanedStop != null;
    }

    /**
     * What the FSM keeps after {@link #stopRecordingWithRetry} gave up while OBS kept writing: the
     * persisted (video-less) row to attach a late-recovered file to, and the sameness baseline --
     * the output had already run for at least the finalize-computed duration when we gave up, so
     * any later OBS-reported duration BELOW the last observation cannot be our output.
     */
    private static final class OrphanedStop {
        final long matchRowId;
        final String thumbPath;
        final long abandonedAtWallMs;
        /** Ratchets up with each consistent observation; a reported duration below it aborts. */
        long lastKnownDurationMs;
        /**
         * MONOTONIC instant {@link #lastKnownDurationMs} was observed (never wall clock -- an NTP
         * step must not warp the floor). Feeds the elapsed-time duration floor: a still-running
         * output's duration grows with monotonic time, so a probed duration far short of
         * {@code lastKnownDurationMs + elapsed} cannot be our output even when it clears the
         * ratchet (see {@link #ORPHAN_STOP_FLOOR_SLACK_MS}). Re-anchored with the ratchet on each
         * consistent observation.
         */
        long lastObservationNanos;
        int attempts;

        OrphanedStop(
                long matchRowId,
                String thumbPath,
                long lastKnownDurationMs,
                long abandonedAtWallMs,
                long lastObservationNanos) {
            this.matchRowId = matchRowId;
            this.thumbPath = thumbPath;
            this.lastKnownDurationMs = lastKnownDurationMs;
            this.abandonedAtWallMs = abandonedAtWallMs;
            this.lastObservationNanos = lastObservationNanos;
        }
    }

    /**
     * The FSM's port onto OBS's live GetRecordStatus, feeding the orphaned-stop sameness guard.
     * Consumer-owned (like {@code RecordingSession.TaggerState} for the tagger) rather than a new
     * {@code ObsRecorder} method because only the orphan retry consumes it; {@code ObsController}
     * implements it. A null answer means OBS cannot be asked right now (disconnected / timed out),
     * which the retry treats as "cannot attribute -- touch nothing".
     */
    public interface RecordStatusProbe {
        RecordOutputStatus probeRecordStatus();
    }

    /**
     * A live record-output snapshot: whether OBS reports the output active, and its reported
     * duration in milliseconds ({@code null} when OBS omitted it -- also unattributable).
     */
    public record RecordOutputStatus(boolean active, Long durationMs) {
    }

    private void openJournal(RecordingSession s, GsiFrame frame) {
        try {
            long now = time.wallMillis();
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
                            time.wallMillis()));
        } catch (RuntimeException e) {
            log.warn("Could not update recording journal for {}: {}", s.getSurrogateId(), e.toString());
        }
    }

    private void appendJournalEvent(
            RecordingSession s, String type, long wallMs, Integer gameClock, String payloadJson) {
        try {
            journal.appendEvent(
                    s.getSurrogateId(),
                    new RecordingEvent(type, wallMs, gameClock, payloadJson, time.wallMillis()));
        } catch (RuntimeException e) {
            log.warn(
                    "Could not append {} event to recording journal for {}: {}",
                    type,
                    s.getSurrogateId(),
                    e.toString());
        }
    }

    /**
     * Serializes a marker into the journaled event payload via Jackson. Hand-rolled escaping missed
     * {@code \r}/{@code \t}/control chars, corrupting any label that carried them; the mapper escapes
     * everything correctly. The wire shape is the shared {@link MarkerPayload} record whose component
     * names are the frozen JSON keys {@code CrashRecoveryRunner.parseMarker} reads back. Returns null on a
     * serialization failure (appendJournalEvent tolerates null).
     */
    private String markerPayload(PendingMarker marker) {
        try {
            return mapper.writeValueAsString(
                    new MarkerPayload(
                            marker.type(),
                            marker.videoOffsetS(),
                            marker.gameClock(),
                            marker.label(),
                            marker.source()));
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize marker payload ({}): {}", marker.type(), e.toString());
            return null;
        }
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
