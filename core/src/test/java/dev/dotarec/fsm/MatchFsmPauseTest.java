package dev.dotarec.fsm;

import static dev.dotarec.gsi.GsiFrames.frame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.dotarec.bridge.EventPublisher;
import dev.dotarec.data.MarkerRepository;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchSummary;
import dev.dotarec.data.PauseRepository;
import dev.dotarec.data.PauseSpan;
import dev.dotarec.data.TestDb;
import dev.dotarec.obs.ObsException;
import dev.dotarec.obs.ObsRecorder;
import dev.dotarec.obs.ThumbnailCapturer;
import dev.dotarec.tagger.EventTagger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the FSM through {@code map.paused} edges against a REAL temp SQLite DB and proves the
 * buffered pause spans are flushed to the {@code pauses} table at finalize. Covers: a closed span
 * (pause then resume), a span left open at finalize being closed to finalize wall-clock, and
 * forceFinalize closing an open pause + writing the row with a null video path when OBS is gone.
 */
class MatchFsmPauseTest {

    /** Fake OBS; {@code stopFails}/{@code connected} toggle the OBS-down path. */
    static final class FakeObs implements ObsRecorder {
        boolean connected = true;
        boolean stopFails = false;
        Instant confirmedAt;
        int stopCalls;
        String savedPath = "C:\\videos\\match.mkv";

        @Override public void connect() { }
        @Override public boolean ensureConnected() { return connected; }
        @Override public boolean isReady() { return connected; }

        @Override public String startRecording() {
            if (!connected) {
                throw new ObsException("OBS is not connected");
            }
            confirmedAt = Instant.now();
            return confirmedAt.toString();
        }

        @Override public String stopRecording() {
            stopCalls++;
            if (stopFails) {
                throw new ObsException("StopRecord rejected");
            }
            return savedPath;
        }

        @Override public Instant recordConfirmedAt() { return confirmedAt; }
    }

    static final class FakeThumbs implements ThumbnailCapturer {
        @Override public Path captureCurrentScene(String id) {
            return Path.of("C:\\videos\\thumbs\\" + id + ".jpg");
        }
    }

    private FakeObs obs;
    private DataSource ds;
    private MatchRepository matches;
    private MarkerRepository markers;
    private PauseRepository pauses;
    private EventPublisher events;
    private MatchFsm fsm;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        ds = TestDb.migrated(dir);
        matches = new MatchRepository(ds);
        markers = new MarkerRepository(ds);
        pauses = new PauseRepository(ds);
        events = mock(EventPublisher.class);
        obs = new FakeObs();
        fsm = new MatchFsm(obs, new FakeThumbs(), new EventTagger(), matches, markers, pauses, events, ds);
    }

    @Test
    void pauseThenResume_persistsOneClosedSpan() {
        start();
        // Unpaused -> paused at wall 1000, paused -> unpaused at wall 2000.
        playing(false, 500);
        playing(true, 1000);   // false->true: open at 1000
        playing(true, 1500);   // still paused: no new span
        playing(false, 2000);  // true->false: close at 2000

        long id = finalizeAndGetRowId();

        List<PauseSpan> spans = pauses.findByMatchId(id);
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).startWall()).isEqualTo(1000L);
        assertThat(spans.get(0).endWall()).isEqualTo(2000L);
    }

    @Test
    void pauseOpenAtFinalize_closedToFinalizeWall() {
        start();
        playing(false, 500);
        playing(true, 1000);   // open at 1000, never resumes before POST_GAME

        long id = finalizeAndGetRowId();

        List<PauseSpan> spans = pauses.findByMatchId(id);
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).startWall()).isEqualTo(1000L);
        // Drained to finalize time -- no dangling null end_wall.
        assertThat(spans.get(0).endWall()).isNotNull();
        assertThat(spans.get(0).endWall()).isGreaterThanOrEqualTo(1000L);
    }

    @Test
    void forceFinalize_closesOpenPause_andWritesRowWhenObsDown() {
        start();
        playing(true, 1000); // open pause

        // Watchdog path with OBS rejecting StopRecord: row still persisted, span still closed.
        obs.stopFails = true;
        fsm.forceFinalize();

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        List<MatchSummary> rows = matches.findAll();
        assertThat(rows).hasSize(1);
        // OBS down -> no video path, row kept pending for enrichment.
        assertThat(rows.get(0).videoPath()).isNull();
        assertThat(rows.get(0).enrichmentState()).isEqualTo("pending");

        List<PauseSpan> spans = pauses.findByMatchId(rows.get(0).id());
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).endWall()).isNotNull();
    }

    @Test
    void forceFinalize_isIdempotent() {
        start();
        playing(true, 1000);
        playing(false, 2000);

        fsm.forceFinalize();
        fsm.forceFinalize(); // no-op once off RECORDING

        assertThat(matches.findAll()).hasSize(1);
        assertThat(obs.stopCalls).isEqualTo(1);
        assertThat(pauses.findByMatchId(matches.findAll().get(0).id())).hasSize(1);
    }

    @Test
    void recordingThatStartsPaused_opensLeadingSpan() {
        // First frame already paused (launched mid-match during a pause): the leading span must open
        // from startRecording, since tagAndObserve only sees edges from the second frame on.
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS").activity("playing")
                .hero("npc_dota_hero_drow_ranger").paused(true).wall(1000).build());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
        playing(false, 2000); // resume: close at 2000

        long id = finalizeAndGetRowId();

        List<PauseSpan> spans = pauses.findByMatchId(id);
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).startWall()).isEqualTo(1000L);
        assertThat(spans.get(0).endWall()).isEqualTo(2000L);
    }

    @Test
    void armStatePausedFrame_doesNotSeedLeadingSpan() {
        // Recording armed from an arm state (HERO_SELECTION) with the paused flag set must NOT seed a
        // leading span: the begins-paused seed is gated to real GAME_IN_PROGRESS gameplay entry, so a
        // draft-phase pause flag can't open a span before the match is rolling.
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_HERO_SELECTION").noHero().activity(null)
                .paused(true).wall(500).build());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
        playing(false, 1000); // first gameplay frame, not paused -> no edge, no span

        long id = finalizeAndGetRowId();

        assertThat(pauses.findByMatchId(id)).isEmpty();
    }

    @Test
    void childWriteFailureRollsBackTheMatchRow() throws Exception {
        // A pause-insert failure mid-finalize must roll back the whole unit of work: no orphan match
        // row left behind, and the FSM still returns to IDLE so the next match can record. The match
        // row + markers + pauses are written in one transaction now, so a child write can't strand a
        // half-persisted row.
        PauseRepository throwingPauses = mock(PauseRepository.class);
        when(throwingPauses.insert(any(), anyLong(), anyLong(), any()))
                .thenThrow(new IllegalStateException("pause write failed"));
        MatchFsm fsmWithBadPauses = new MatchFsm(
                obs, new FakeThumbs(), new EventTagger(), matches, markers, throwingPauses, events, ds);

        // Open a pause so finalize has a span to persist -- which then throws and triggers rollback.
        fsmWithBadPauses.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS")
                .activity("playing").hero("npc_dota_hero_drow_ranger").wall(0).build());
        fsmWithBadPauses.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS")
                .activity("playing").hero("npc_dota_hero_drow_ranger").paused(true).wall(1000).build());
        fsmWithBadPauses.onFrame(frame().state("DOTA_GAMERULES_STATE_POST_GAME").noHero().build());

        assertThat(fsmWithBadPauses.getState()).isEqualTo(MatchState.IDLE);
        assertThat(matches.findAll()).isEmpty(); // the match insert rolled back -- no orphan row
    }

    // ---- helpers -----------------------------------------------------------

    private void start() {
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS").activity("playing")
                .hero("npc_dota_hero_drow_ranger").wall(0).build());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
    }

    private void playing(boolean paused, long wall) {
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS").activity("playing")
                .hero("npc_dota_hero_drow_ranger").paused(paused).wall(wall).build());
    }

    private long finalizeAndGetRowId() {
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_POST_GAME").noHero().build());
        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        List<MatchSummary> rows = matches.findAll();
        assertThat(rows).hasSize(1);
        return rows.get(0).id();
    }
}
