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
import dev.dotarec.fsm.MatchFsmTest.FakeObs;
import dev.dotarec.fsm.MatchFsmTest.FakeThumbs;
import dev.dotarec.gsi.GsiFrame;
import dev.dotarec.obs.ObsRestarter;
import dev.dotarec.tagger.EventTagger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Self-healing: an OBS that lost its graphics device (a GPU driver update while it runs) still
 * accepts StartRecord but writes an empty file, and only a restart fixes it. The FSM spots that and
 * restarts OBS through {@link ObsRestarter}; the next in-match frame records once OBS is back.
 */
class MatchFsmHealingTest {

    /** Records restart requests; the "restart" itself is the test flipping the fake OBS healthy. */
    static final class FakeRestarter implements ObsRestarter {
        final List<String> reasons = new ArrayList<>();

        @Override
        public boolean restartObs(String reason) {
            reasons.add(reason);
            return true;
        }
    }

    private static final long MATCH = 7_712_345_678L;
    private static final String IN_PROGRESS = "DOTA_GAMERULES_STATE_GAME_IN_PROGRESS";
    private static final String POST_GAME = "DOTA_GAMERULES_STATE_POST_GAME";

    private FakeObs obs;
    private FakeRestarter restarter;
    private MatchRepository matches;
    private RecordingSessionRepository journal;
    private final AtomicLong nanos = new AtomicLong(5_000_000_000_000L);
    private MatchFsm fsm;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        var ds = TestDb.migrated(dir);
        matches = new MatchRepository(ds);
        journal = new RecordingSessionRepository(ds);
        obs = new FakeObs();
        restarter = new FakeRestarter();
        SettingsStore settings = mock(SettingsStore.class);
        when(settings.get()).thenReturn(new SettingsStore.Settings());
        fsm = new MatchFsm(obs, obs, new FakeThumbs(), new EventTagger(), matches,
                new MarkerRepository(ds), new PauseRepository(ds), journal,
                mock(EventPublisher.class), ds, mock(ClipService.class), settings,
                new ObjectMapper(), new TimeSource(nanos::get, System::currentTimeMillis),
                restarter);
    }

    private GsiFrame playing() {
        return frame().state(IN_PROGRESS).activity("playing").matchId(MATCH)
                .mono(nanos.get()).build();
    }

    private void advanceSeconds(long s) {
        nanos.addAndGet(s * 1_000_000_000L);
    }

    /** OBS comes back healthy after its restart. */
    private void obsRestarted() {
        obs.renderBroken = false;
        obs.startNotConfirmed = false;
        obs.recordStatus = null;
    }

    @Test
    void obsThatCantRender_isRestartedBeforeRecording_andTheMatchRecordsOnceItsBack() {
        obs.renderBroken = true;
        fsm.onFrame(playing());

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        assertThat(obs.startCalls).as("no StartRecord into a broken OBS").isZero();
        assertThat(restarter.reasons).hasSize(1);

        obsRestarted();
        advanceSeconds(MatchFsm.RENDER_PROBE_INTERVAL_MS / 1000);
        fsm.onFrame(playing());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
    }

    @Test
    void renderProbe_isThrottled_acrossTheFrameFeed() {
        obs.renderBroken = true;
        fsm.onFrame(playing());
        obs.renderBroken = false; // healthy again, but the last probe is still fresh
        fsm.onFrame(playing());
        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);

        advanceSeconds(MatchFsm.RENDER_PROBE_INTERVAL_MS / 1000);
        fsm.onFrame(playing());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
    }

    @Test
    void startOBSNeverConfirms_restartsObs_andTheNextFrameRetries() {
        obs.startNotConfirmed = true;
        fsm.onFrame(playing());

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        assertThat(restarter.reasons).hasSize(1);

        obsRestarted();
        fsm.onFrame(playing());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
    }

    @Test
    void emptyRecording_isDiscarded_andTheRestOfTheMatchRecorded() {
        fsm.onFrame(playing());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);

        advanceSeconds(12);
        obs.recordStatus = new MatchFsm.RecordOutputStatus(true, 12_000L, 0L);
        obs.renderBroken = true;
        fsm.superviseRecording();

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        assertThat(matches.findAll()).as("an empty recording leaves no match row").isEmpty();
        assertThat(journal.findUnfinished()).isEmpty();
        assertThat(restarter.reasons).hasSize(1);

        obsRestarted();
        fsm.onFrame(playing());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
        advanceSeconds(1_800);
        fsm.onFrame(frame().state(POST_GAME).matchId(MATCH).mono(nanos.get()).build());

        List<MatchSummary> rows = matches.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).dotaMatchId()).isEqualTo(MATCH);
    }

    @Test
    void stalledRecording_keepsWhatWasRecorded_andRecordsTheRest() {
        fsm.onFrame(playing());
        advanceSeconds(20);
        obs.recordStatus = new MatchFsm.RecordOutputStatus(true, 20_000L, 30_000_000L);
        fsm.superviseRecording();

        obs.renderBroken = true;
        advanceSeconds(10);
        fsm.superviseRecording();
        assertThat(fsm.getState()).as("10s flat is not yet a stall")
                .isEqualTo(MatchState.RECORDING);
        advanceSeconds(10);
        fsm.superviseRecording();

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        assertThat(matches.findAll()).as("the partial recording is kept").hasSize(1);

        obsRestarted();
        advanceSeconds(MatchFsm.RENDER_PROBE_INTERVAL_MS / 1000);
        fsm.onFrame(playing());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
        advanceSeconds(600);
        fsm.onFrame(frame().state(POST_GAME).matchId(MATCH).mono(nanos.get()).build());

        List<MatchSummary> rows = matches.findAll();
        assertThat(rows).hasSize(2);
        assertThat(rows).filteredOn(r -> r.dotaMatchId() != null)
                .as("the first part keeps the match link").hasSize(1);
    }

    @Test
    void flatOutput_withAWorkingRenderer_isLeftAlone() {
        fsm.onFrame(playing());
        advanceSeconds(60);
        obs.recordStatus = new MatchFsm.RecordOutputStatus(true, 60_000L, 0L);
        fsm.superviseRecording();

        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
        assertThat(restarter.reasons).isEmpty();
    }
}
