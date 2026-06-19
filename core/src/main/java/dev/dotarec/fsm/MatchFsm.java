package dev.dotarec.fsm;

import dev.dotarec.bridge.EventPublisher;
import dev.dotarec.data.MarkerRepository;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.NewMatch;
import dev.dotarec.gsi.GsiFrame;
import dev.dotarec.obs.ObsException;
import dev.dotarec.obs.ObsRecorder;
import dev.dotarec.obs.ThumbnailCapturer;
import dev.dotarec.tagger.EventTagger;
import dev.dotarec.tagger.PendingMarker;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final EventPublisher events;

    private volatile MatchState state = MatchState.IDLE;
    private RecordingSession session;

    public MatchFsm(
            ObsRecorder obs,
            ThumbnailCapturer thumbnails,
            EventTagger tagger,
            MatchRepository matches,
            MarkerRepository markers,
            EventPublisher events) {
        this.obs = obs;
        this.thumbnails = thumbnails;
        this.tagger = tagger;
        this.matches = matches;
        this.markers = markers;
        this.events = events;
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

    private void startRecording(GsiFrame frame) {
        if (!obs.ensureConnected()) {
            // OBS down: stay IDLE and retry on the next frame. Never throw on the GSI thread.
            log.warn("Cannot start recording: OBS not connected; will retry on next frame");
            return;
        }
        try {
            obs.startRecording();
        } catch (ObsException e) {
            log.warn("StartRecord rejected by OBS: {}; staying IDLE", e.getMessage());
            return;
        }

        RecordingSession s = new RecordingSession();
        s.setSurrogateId(UUID.randomUUID().toString());
        // OUTPUT_STARTED may land microseconds after StartRecord returns; recordConfirmedAt() is the
        // authoritative anchor. Fall back to "now" if the confirm has not posted yet so offsets are
        // still sane (the gap is sub-frame at 10Hz).
        Instant confirmed = obs.recordConfirmedAt();
        long anchor = confirmed != null ? confirmed.toEpochMilli() : frame.wallClockMillis();
        s.setRecordConfirmedWallMs(anchor);
        s.setRecordStartedWallMs(anchor);
        s.observe(frame);
        s.setLastFrame(frame);

        this.session = s;
        this.state = MatchState.RECORDING;
        log.info("Recording started (surrogate {}), anchor={}", s.getSurrogateId(), anchor);
    }

    private void tagAndObserve(GsiFrame frame) {
        RecordingSession s = this.session;
        if (s == null) {
            return;
        }
        List<PendingMarker> detected =
                tagger.diff(s.getLastFrame(), frame, s.getRecordConfirmedWallMs(), LIVE_DURATION_CLAMP);
        if (!detected.isEmpty()) {
            s.addMarkers(detected);
        }
        s.observe(frame);
        s.setLastFrame(frame);
    }

    private void finalizeRecording() {
        RecordingSession s = this.session;
        // Defensive: nothing to finalize -> reset to IDLE.
        if (s == null) {
            this.state = MatchState.IDLE;
            return;
        }
        this.state = MatchState.STOPPING;

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

            videoPath = obs.stopRecording();
        } catch (ObsException e) {
            // OBS rejected StopRecord: we still persist what we have so markers/stats survive.
            log.warn("StopRecord failed: {}; persisting match row without video path", e.getMessage());
        }

        long now = System.currentTimeMillis();
        long anchor = s.getRecordConfirmedWallMs();
        // Duration from the confirmed start to stop; clamp to >= 0.
        int durationS = (int) Math.max(0, (now - anchor) / 1000);

        long matchRowId = persistMatch(s, videoPath, thumbPath, durationS, now);
        persistMarkers(matchRowId, s, durationS);

        publishRecorded(matchRowId, s, durationS);

        this.session = null;
        this.state = MatchState.IDLE;
        log.info("Recording finalized -> match row {} ({} markers, {}s)",
                matchRowId, s.getMarkers().size(), durationS);
    }

    private long persistMatch(
            RecordingSession s, String videoPath, String thumbPath, int durationS, long now) {
        Long dotaMatchId = s.getMatchId() != 0L ? s.getMatchId() : null;
        NewMatch row =
                new NewMatch(
                        dotaMatchId,
                        "match",
                        // result/MMR/lobby/etc. are enrichment's job; live GSI can't reliably know
                        // win/loss for the player without team context, so leave them null.
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
                        null, // file_size_bytes (retention pass / enrichment fills this)
                        false,
                        now, // created_at
                        s.getRecordStartedWallMs());
        return matches.insert(row);
    }

    private void persistMarkers(long matchRowId, RecordingSession s, int durationS) {
        for (PendingMarker m : s.getMarkers()) {
            // Re-clamp the live offset to the now-known real duration so a marker can't sit past the
            // end of the file (live tagging used a generous bound).
            double offset = Math.min(m.videoOffsetS(), durationS);
            markers.insert(matchRowId, m.type(), offset, m.gameClock(), m.label(), m.source());
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
