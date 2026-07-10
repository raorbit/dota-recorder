package dev.dotarec.fsm;

import static dev.dotarec.gsi.GsiFrames.frame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.bridge.EventPublisher;
import dev.dotarec.clip.ClipService;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.config.TimeSource;
import dev.dotarec.data.MarkerRepository;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchSummary;
import dev.dotarec.data.PauseRepository;
import dev.dotarec.data.RecordingSessionRepository;
import dev.dotarec.data.TestDb;
import dev.dotarec.obs.ObsException;
import dev.dotarec.obs.ObsRecorder;
import dev.dotarec.obs.ThumbnailCapturer;
import dev.dotarec.tagger.EventTagger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the retained-ownership path for a stop-failed output: finalize gives up on a double
 * StopRecord failure while OBS keeps writing, so instead of discarding its knowledge that the
 * stuck output is its own, the FSM retains the persisted row + a duration baseline and runs
 * bounded {@link MatchFsm#retryOrphanedStop()} ticks. Proves: the retained state exists after the
 * give-up; a consistent (still-ours) output is stopped and its late-recovered file is attached to
 * the persisted row; the sameness guard aborts on a duration reset (a fresh, e.g. hand-started,
 * output must never be touched) AND on the elapsed-time floor (a near-zero baseline makes the
 * ratchet vacuous, so a fresh output that happens to clear it must still be refused -- while a
 * genuinely continuous output tracks monotonic elapsed time across ticks, within the slack); an
 * inactive output, a new match arming, and the user's manual stopOrphanedRecording all cancel the
 * retries; and an unanswerable OBS burns bounded attempts rather than retrying forever.
 */
class OrphanedStopRetryTest {

    /** Fake OBS whose StopRecord fails a set number of times and whose record status is scripted. */
    static final class FakeObs implements ObsRecorder, MatchFsm.RecordStatusProbe {
        boolean connected = true;
        int stopFailuresRemaining;
        boolean recording;
        Instant confirmedAt;
        int startCalls;
        int stopCalls;
        String savedPath = "C:\\videos\\match.mkv";
        /** What probeRecordStatus answers; null models an unanswerable OBS. Tests drive it directly. */
        MatchFsm.RecordOutputStatus recordStatus;

        @Override public void connect() { }
        @Override public boolean ensureConnected() { return connected; }
        @Override public boolean isReady() { return connected; }

        @Override public String startRecording() {
            startCalls++;
            confirmedAt = Instant.now();
            recording = true;
            return confirmedAt.toString();
        }

        @Override public String stopRecording() {
            stopCalls++;
            if (stopFailuresRemaining > 0) {
                stopFailuresRemaining--;
                throw new ObsException("StopRecord timed out");
            }
            recording = false;
            return savedPath;
        }

        @Override public boolean isRecording() { return recording; }

        @Override public Instant recordConfirmedAt() { return confirmedAt; }

        @Override public MatchFsm.RecordOutputStatus probeRecordStatus() { return recordStatus; }
    }

    static final class FakeThumbs implements ThumbnailCapturer {
        @Override public Path captureCurrentScene(String id) {
            return Path.of("C:\\videos\\thumbs\\" + id + ".jpg");
        }
    }

    private static final long ANCHOR_NANOS = 5_000_000_000_000L;
    /** The recording runs 300s (monotonic) before finalize, so the duration baseline is 300_000ms. */
    private static final long RECORDED_NANOS = 300L * 1_000_000_000L;

    private FakeObs obs;
    private MatchRepository matches;
    private MatchFsm fsm;

    /** Controllable monotonic clock: the start anchor, then stepped forward before finalize. */
    private final AtomicLong nano = new AtomicLong(ANCHOR_NANOS);

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        DataSource ds = TestDb.migrated(dir);
        matches = new MatchRepository(ds);
        MarkerRepository markers = new MarkerRepository(ds);
        PauseRepository pauses = new PauseRepository(ds);
        RecordingSessionRepository journal = new RecordingSessionRepository(ds);
        EventPublisher events = mock(EventPublisher.class);
        obs = new FakeObs();
        ClipService clipService = mock(ClipService.class);
        SettingsStore settings = mock(SettingsStore.class);
        when(settings.get()).thenReturn(new SettingsStore.Settings());
        fsm = new MatchFsm(obs, obs, new FakeThumbs(), new EventTagger(), matches, markers, pauses,
                journal, events, ds, clipService, settings, new ObjectMapper(),
                new TimeSource(nano::get, System::currentTimeMillis));
    }

    /**
     * Drives record -> double StopRecord failure -> give-up, and returns the persisted video-less
     * row. Afterwards OBS still reports recording and the FSM retains orphan ownership.
     */
    private MatchSummary giveUp() {
        return giveUpAfter(RECORDED_NANOS);
    }

    /**
     * {@link #giveUp()} with a controllable recorded span: the monotonic clock advances by
     * {@code recordedNanos} between OUTPUT_STARTED and finalize, so the retained duration baseline
     * is {@code recordedNanos} in ms. A tiny span models the vacuous-ratchet case (a stop that
     * failed seconds after OUTPUT_STARTED).
     */
    private MatchSummary giveUpAfter(long recordedNanos) {
        obs.stopFailuresRemaining = 2;
        fsm.onFrame(frame().matchId(111L).state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS")
                .activity("playing").hero("npc_dota_hero_lina").build());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
        nano.set(ANCHOR_NANOS + recordedNanos);
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_POST_GAME").noHero().build());

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        assertThat(obs.stopCalls).isEqualTo(2);
        assertThat(obs.recording).as("OBS kept writing past the give-up").isTrue();
        return matches.findAll().get(0);
    }

    @Test
    void giveUp_retainsOrphanOwnershipOfThePersistedRow() {
        MatchSummary row = giveUp();

        assertThat(row.videoPath()).isNull();
        assertThat(fsm.hasOrphanedStopRetry())
                .as("the FSM must not discard its knowledge that the stuck output is its own")
                .isTrue();
    }

    @Test
    void consistentRetry_stopsTheOutput_andAttachesTheVideoToThePersistedRow() {
        MatchSummary row = giveUp();
        // Duration grew past the 300s baseline: still our abandoned output.
        obs.recordStatus = new MatchFsm.RecordOutputStatus(true, 305_000L);

        fsm.retryOrphanedStop();

        assertThat(obs.stopCalls).isEqualTo(3);
        assertThat(obs.recording).isFalse();
        assertThat(fsm.hasOrphanedStopRetry()).isFalse();
        MatchSummary recovered = matches.findById(row.id()).orElseThrow();
        assertThat(recovered.videoPath())
                .as("the late-recovered file is attached to the row that holds the markers")
                .isEqualTo("C:\\videos\\match.mkv");
        assertThat(recovered.thumbPath())
                .as("the thumbnail captured before the failed stop survives the attach")
                .isEqualTo(row.thumbPath());
    }

    @Test
    void retry_abortsWhenTheOutputRestarted_durationResetBelowLastObservation() {
        MatchSummary row = giveUp();
        // 20s of output against a 300s baseline: a FRESH recording (e.g. hand-started in the OBS
        // window) took the output's place. The retry must never touch it.
        obs.recordStatus = new MatchFsm.RecordOutputStatus(true, 20_000L);

        fsm.retryOrphanedStop();

        assertThat(obs.stopCalls).as("an unattributable output is never stopped").isEqualTo(2);
        assertThat(obs.recording).isTrue();
        assertThat(fsm.hasOrphanedStopRetry()).isFalse();
        assertThat(matches.findById(row.id()).orElseThrow().videoPath()).isNull();
    }

    @Test
    void retry_abortsOnTheElapsedFloor_whenANearZeroBaselineWouldLetAnyOutputPass() {
        // The stop failed 3s after OUTPUT_STARTED, so the ratchet baseline is only 3_000ms and ANY
        // active output clears it. 30s later (the watchdog cadence) the wedged output has ended on
        // its own and a hand-started recording, 8s old, holds the output: 8_000ms >= the 3_000ms
        // ratchet, but far below the elapsed floor (3s + 30s - 5s slack = 28s). The floor must
        // refuse it -- stopping here would cut the USER'S recording and mis-attach their file.
        MatchSummary row = giveUpAfter(3L * 1_000_000_000L);
        nano.addAndGet(30L * 1_000_000_000L);
        obs.recordStatus = new MatchFsm.RecordOutputStatus(true, 8_000L);

        fsm.retryOrphanedStop();

        assertThat(obs.stopCalls).as("an output below the elapsed floor is never stopped").isEqualTo(2);
        assertThat(obs.recording).as("the user's fresh recording keeps rolling").isTrue();
        assertThat(fsm.hasOrphanedStopRetry()).isFalse();
        assertThat(matches.findById(row.id()).orElseThrow().videoPath()).isNull();
    }

    @Test
    void continuousOutput_trackingElapsedAcrossTicks_isStillStoppedAndAttached() {
        MatchSummary row = giveUp();
        // Tick 1, 30s after the give-up: the output grew with elapsed time (300s -> 330s), so it
        // clears ratchet and floor (325s) -- but this StopRecord fails too, which must re-anchor
        // the observation (duration AND instant) instead of cancelling.
        obs.stopFailuresRemaining = 1;
        nano.addAndGet(30L * 1_000_000_000L);
        obs.recordStatus = new MatchFsm.RecordOutputStatus(true, 330_000L);
        fsm.retryOrphanedStop();
        assertThat(fsm.hasOrphanedStopRetry()).as("a failed stop burns the attempt, not the state").isTrue();

        // Tick 2, another 30s: still growing with elapsed time against the re-anchored observation
        // (floor 330s + 30s - 5s = 355s). Were the instant not re-anchored, the stale floor
        // (330s + 60s - 5s = 385s) would wrongly cancel here.
        nano.addAndGet(30L * 1_000_000_000L);
        obs.recordStatus = new MatchFsm.RecordOutputStatus(true, 360_000L);
        fsm.retryOrphanedStop();

        assertThat(obs.recording).isFalse();
        assertThat(fsm.hasOrphanedStopRetry()).isFalse();
        assertThat(matches.findById(row.id()).orElseThrow().videoPath())
                .as("a continuously-running output is still recovered and attached")
                .isEqualTo("C:\\videos\\match.mkv");
    }

    @Test
    void floorSlack_toleratesADurationLaggingAFewSecondsUnderExactElapsed() {
        MatchSummary row = giveUp();
        // 30s of monotonic time elapsed but OBS reports only +27s of output growth -- probe
        // latency, second-granularity truncation, scheduler jitter. That lag sits inside the 5s
        // slack (floor 325s), so the output is still attributed to us and recovered.
        nano.addAndGet(30L * 1_000_000_000L);
        obs.recordStatus = new MatchFsm.RecordOutputStatus(true, 327_000L);

        fsm.retryOrphanedStop();

        assertThat(obs.recording).isFalse();
        assertThat(fsm.hasOrphanedStopRetry()).isFalse();
        assertThat(matches.findById(row.id()).orElseThrow().videoPath())
                .isEqualTo("C:\\videos\\match.mkv");
    }

    @Test
    void retry_endsQuietlyWhenTheOutputWentInactive() {
        MatchSummary row = giveUp();
        obs.recordStatus = new MatchFsm.RecordOutputStatus(false, null);

        fsm.retryOrphanedStop();

        assertThat(obs.stopCalls).isEqualTo(2);
        assertThat(fsm.hasOrphanedStopRetry())
                .as("output gone -> nothing left to stop; the boot orphan scan adopts the file")
                .isFalse();
        assertThat(matches.findById(row.id()).orElseThrow().videoPath()).isNull();
    }

    @Test
    void retry_doesNotStopWhenTheDurationIsUnreadable_butStaysArmed() {
        giveUp();
        obs.recordStatus = new MatchFsm.RecordOutputStatus(true, null);

        fsm.retryOrphanedStop();

        assertThat(obs.stopCalls).as("no attribution -> no stop").isEqualTo(2);
        assertThat(fsm.hasOrphanedStopRetry()).as("the attempt is burned, not the state").isTrue();
    }

    @Test
    void unanswerableObs_burnsBoundedAttempts_thenGivesUpLoudly() {
        giveUp();
        obs.recordStatus = null;

        for (int i = 0; i < MatchFsm.ORPHAN_STOP_MAX_ATTEMPTS - 1; i++) {
            fsm.retryOrphanedStop();
        }
        assertThat(fsm.hasOrphanedStopRetry()).as("still bounded-retrying").isTrue();

        fsm.retryOrphanedStop();

        assertThat(fsm.hasOrphanedStopRetry()).as("attempts exhausted -> state cleared").isFalse();
        assertThat(obs.stopCalls).as("never stopped what it could not attribute").isEqualTo(2);
        // The truthful ObsHealth.recording (modeled by the fake's flag) stays, so the renderer's
        // manual stop affordance survives the give-up.
        assertThat(obs.recording).isTrue();
    }

    @Test
    void newMatchArm_cancelsTheRetries() {
        giveUp();
        obs.recordStatus = new MatchFsm.RecordOutputStatus(true, 305_000L);

        // A fresh match arms: its start (and the corrective StopRecord inside the real OBS
        // controller) owns the output now, so the orphan retries must stand down first.
        fsm.onFrame(frame().matchId(222L).state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS")
                .activity("playing").hero("npc_dota_hero_rubick").build());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
        assertThat(fsm.hasOrphanedStopRetry()).isFalse();

        fsm.retryOrphanedStop();

        assertThat(obs.stopCalls).as("a cancelled retry must not cut the fresh recording").isEqualTo(2);
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
    }

    @Test
    void userStopOrphanedRecording_clearsTheRetryState() {
        MatchSummary row = giveUp();

        assertThat(fsm.stopOrphanedRecording()).isTrue();

        assertThat(fsm.hasOrphanedStopRetry())
                .as("the user-initiated stop supersedes the bounded retries")
                .isFalse();
        assertThat(obs.recording).isFalse();
        // The manual path keeps its documented contract: no import here, next boot's scan adopts.
        assertThat(matches.findById(row.id()).orElseThrow().videoPath()).isNull();
    }

    @Test
    void successfulStop_neverRetainsOrphanState() {
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS").activity("playing")
                .hero("npc_dota_hero_lina").build());
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_POST_GAME").noHero().build());

        assertThat(matches.findAll().get(0).videoPath()).isEqualTo("C:\\videos\\match.mkv");
        assertThat(fsm.hasOrphanedStopRetry()).isFalse();
    }
}
