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
import dev.dotarec.obs.setup.EncoderFallback;
import dev.dotarec.tagger.EventTagger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A hardware encoder OBS offers but can't open: OBS accepts every StartRecord and never starts the
 * output, before and after a restart. The FSM restarts OBS on the first such start; on the second in
 * a row it first asks {@link EncoderFallback} to switch an auto-picked hardware encoder to x264, so
 * the OBS that restart relaunches records with x264.
 */
class MatchFsmEncoderFallbackTest {

    private static final long MATCH = 7_712_345_678L;
    private static final String IN_PROGRESS = "DOTA_GAMERULES_STATE_GAME_IN_PROGRESS";
    private static final String POST_GAME = "DOTA_GAMERULES_STATE_POST_GAME";

    /** The restarts and encoder fallbacks the FSM asked for, in order. */
    private final List<String> asked = new ArrayList<>();
    /** What the fallback answers; false models a Settings pick it keeps, or x264 already. */
    private boolean switches = true;
    private final AtomicLong nanos = new AtomicLong(5_000_000_000_000L);
    private FakeObs obs;
    private MatchFsm fsm;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        var ds = TestDb.migrated(dir);
        obs = new FakeObs();
        SettingsStore settings = mock(SettingsStore.class);
        when(settings.get()).thenReturn(new SettingsStore.Settings());
        fsm = new MatchFsm(obs, obs, new FakeThumbs(), new EventTagger(), new MatchRepository(ds),
                new MarkerRepository(ds), new PauseRepository(ds), new RecordingSessionRepository(ds),
                mock(EventPublisher.class), ds, mock(ClipService.class), settings,
                new ObjectMapper(), new TimeSource(nanos::get, System::currentTimeMillis),
                reason -> {
                    asked.add("restart");
                    return true;
                });
        fsm.setEncoderFallback(() -> {
            asked.add("x264");
            return switches;
        });
    }

    private GsiFrame playing(long matchId) {
        return frame().state(IN_PROGRESS).activity("playing").matchId(matchId)
                .mono(nanos.get()).build();
    }

    @Test
    void secondStartInARowThatNeverStarts_switchesToX264_beforeTheRestart() {
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
    void startsThatKeepFailing_askEverySecondFailure_notEveryOne() {
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
}
