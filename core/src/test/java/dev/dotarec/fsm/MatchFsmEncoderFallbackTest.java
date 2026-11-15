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
import dev.dotarec.data.PauseRepository;
import dev.dotarec.data.RecordingSessionRepository;
import dev.dotarec.data.TestDb;
import dev.dotarec.fsm.MatchFsmTest.FakeObs;
import dev.dotarec.fsm.MatchFsmTest.FakeThumbs;
import dev.dotarec.gsi.GsiFrame;
import dev.dotarec.obs.ObsProcess;
import dev.dotarec.obs.setup.EncoderFallback;
import dev.dotarec.tagger.EventTagger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A hardware encoder OBS offers but can't open: OBS accepts every StartRecord and never starts the
 * output, before and after a restart. The FSM restarts OBS on the first such start; once a second
 * run of OBS in a row fails the same way, it first asks {@link EncoderFallback} to switch an
 * auto-picked hardware encoder to x264, so the OBS that restart relaunches records with x264.
 */
class MatchFsmEncoderFallbackTest {

    private static final long MATCH = 7_712_345_678L;
    private static final String IN_PROGRESS = "DOTA_GAMERULES_STATE_GAME_IN_PROGRESS";
    private static final String POST_GAME = "DOTA_GAMERULES_STATE_POST_GAME";

    /** The restarts and encoder fallbacks the FSM asked for, in order. */
    private final List<String> asked = new ArrayList<>();
    /** What the fallback answers; false models a Settings pick it keeps, or x264 already. */
    private boolean switches = true;
    /**
     * What the restarter answers. True ends OBS, and Electron relaunches it: a new process, reached
     * over a new connection. False models a restart it refuses, since it spaces restarts out.
     */
    private boolean restarts = true;
    private final AtomicLong nanos = new AtomicLong(5_000_000_000_000L);
    private FakeObs obs;
    private MatchFsm fsm;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        var ds = TestDb.migrated(dir);
        obs = new FakeObs();
        obs.process = new ObsProcess(4_242L, Instant.parse("2026-11-15T18:00:00Z"));
        SettingsStore settings = mock(SettingsStore.class);
        when(settings.get()).thenReturn(new SettingsStore.Settings());
        fsm = new MatchFsm(obs, obs, new FakeThumbs(), new EventTagger(), new MatchRepository(ds),
                new MarkerRepository(ds), new PauseRepository(ds), new RecordingSessionRepository(ds),
                mock(EventPublisher.class), ds, mock(ClipService.class), settings,
                new ObjectMapper(), new TimeSource(nanos::get, System::currentTimeMillis),
                reason -> {
                    asked.add("restart");
                    if (restarts) {
                        relaunchObs();
                    }
                    return restarts;
                });
        fsm.setEncoderFallback(() -> {
            asked.add("x264");
            return switches;
        });
    }

    /**
     * The OBS that was running exits and a new one answers over a new connection. A process the
     * core couldn't name stays unnamed in its relaunch.
     */
    private void relaunchObs() {
        if (obs.process != null) {
            obs.exited.add(obs.process);
            obs.process = new ObsProcess(
                    obs.process.pid() + 1, obs.process.startedAt().plusSeconds(60));
        }
        obs.epoch++;
    }

    private GsiFrame playing(long matchId) {
        return frame().state(IN_PROGRESS).activity("playing").matchId(matchId)
                .mono(nanos.get()).build();
    }

    @Test
    void aSecondRunOfObsThatNeverStarts_switchesToX264_beforeTheRestart() {
        obs.startNotConfirmed = true;

        fsm.onFrame(playing(MATCH));
        assertThat(asked).as("a first one is a plain restart").containsExactly("restart");

        fsm.onFrame(playing(MATCH));
        assertThat(asked)
                .as("the profile is switched before the restart that relaunches OBS on it")
                .containsExactly("restart", "x264", "restart");
        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);

        // The relaunched OBS opens x264 and records.
        obs.startNotConfirmed = false;
        fsm.onFrame(playing(MATCH));
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
    }

    @Test
    void aStartThatRecords_startsTheCountOver() {
        obs.startNotConfirmed = true;
        fsm.onFrame(playing(MATCH));
        obs.startNotConfirmed = false;
        fsm.onFrame(playing(MATCH));
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
        fsm.onFrame(frame().state(POST_GAME).matchId(MATCH).mono(nanos.get()).build());
        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);

        // The next match's failed start follows one that recorded, so it is a first one again.
        obs.startNotConfirmed = true;
        fsm.onFrame(playing(MATCH + 1));

        assertThat(asked).containsExactly("restart", "restart");
    }

    @Test
    void startsThatKeepFailing_askEverySecondRun_notOnEveryFailure() {
        // The fallback keeps a Settings pick, so the starts go on failing; the user may set the
        // encoder to Auto meanwhile, so it is asked again, but not on every failure.
        switches = false;
        obs.startNotConfirmed = true;

        for (int i = 0; i < 4; i++) {
            fsm.onFrame(playing(MATCH));
        }

        assertThat(asked).containsExactly(
                "restart", "x264", "restart", "restart", "x264", "restart");
    }

    @Test
    void otherStartFailures_neitherCount_norStartTheCountOver() {
        obs.startNotConfirmed = true;
        fsm.onFrame(playing(MATCH));
        // OBS rejecting the request, or not answering it, says nothing about the encoder.
        obs.startNotConfirmed = false;
        obs.startThrows = true;
        fsm.onFrame(playing(MATCH));
        assertThat(asked).containsExactly("restart");

        obs.startThrows = false;
        obs.startNotConfirmed = true;
        fsm.onFrame(playing(MATCH));

        assertThat(asked).containsExactly("restart", "x264", "restart");
    }

    @Test
    void theSameObsFailingAgain_afterARefusedRestart_isNoFreshObsFailing() {
        // A dialog left open fails every start until OBS is restarted, and the restarter spaces
        // restarts out, so the same OBS keeps failing in the meantime.
        restarts = false;
        obs.startNotConfirmed = true;

        for (int i = 0; i < 3; i++) {
            fsm.onFrame(playing(MATCH));
        }
        assertThat(asked).containsExactly("restart", "restart", "restart");

        // A restart goes through at last, and the OBS it relaunches fails the same way.
        restarts = true;
        fsm.onFrame(playing(MATCH));
        fsm.onFrame(playing(MATCH));

        assertThat(asked).containsExactly(
                "restart", "restart", "restart", "restart", "x264", "restart");
    }

    @Test
    void aReconnectToTheSameObs_isNoFreshObs() {
        restarts = false;
        obs.startNotConfirmed = true;
        fsm.onFrame(playing(MATCH));

        obs.epoch++; // a websocket blip: a new connection, to the same OBS process
        fsm.onFrame(playing(MATCH));

        assertThat(asked).containsExactly("restart", "restart");
    }

    @Test
    void whereTheObsCantBeNamed_aNewConnectionTellsTheRuns() {
        obs.process = null;
        restarts = false;
        obs.startNotConfirmed = true;
        fsm.onFrame(playing(MATCH));
        fsm.onFrame(playing(MATCH));
        assertThat(asked).as("still the same connection").containsExactly("restart", "restart");

        obs.epoch++; // reconnected, to the OBS a later restart relaunched
        fsm.onFrame(playing(MATCH));

        assertThat(asked).containsExactly("restart", "restart", "x264", "restart");
    }

    @Test
    void anObsThatCantBeNamed_isAnotherRun_onceTheCountedOneHasExited() {
        restarts = false;
        obs.startNotConfirmed = true;
        ObsProcess counted = obs.process;
        fsm.onFrame(playing(MATCH));

        // Reconnected, to an OBS the process scan couldn't name.
        obs.process = null;
        obs.epoch++;
        fsm.onFrame(playing(MATCH));
        assertThat(asked).as("the counted OBS still runs, so this is it").containsExactly(
                "restart", "restart");

        obs.exited.add(counted);
        fsm.onFrame(playing(MATCH));

        assertThat(asked).containsExactly("restart", "restart", "x264", "restart");
    }
}
