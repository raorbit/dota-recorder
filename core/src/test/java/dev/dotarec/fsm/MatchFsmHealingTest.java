package dev.dotarec.fsm;

import static dev.dotarec.gsi.GsiFrames.frame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import dev.dotarec.obs.ObsProcess;
import dev.dotarec.obs.ObsRestarter;
import dev.dotarec.tagger.EventTagger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Self-healing: an OBS that lost its graphics device (a GPU driver update while it runs) still
 * accepts StartRecord but writes an empty file, and only a restart fixes it. The FSM spots that and
 * restarts OBS through {@link ObsRestarter}; the next in-match frame records once OBS is back.
 */
class MatchFsmHealingTest {

    /** Records restart requests; the "restart" itself is the test flipping the fake OBS healthy. */
    static final class FakeRestarter implements ObsRestarter {
        final List<String> reasons = new ArrayList<>();
        /** False models ObsHealer refusing (cooldown or hourly cap): nothing is restarted. */
        boolean succeeds = true;

        @Override
        public boolean restartObs(String reason) {
            reasons.add(reason);
            return succeeds;
        }
    }

    private static final long MATCH = 7_712_345_678L;
    private static final String IN_PROGRESS = "DOTA_GAMERULES_STATE_GAME_IN_PROGRESS";
    private static final String POST_GAME = "DOTA_GAMERULES_STATE_POST_GAME";

    /** The OBS run a recording starts on, and the one Electron relaunches once that one crashed. */
    private static final ObsProcess OBS_RUN =
            new ObsProcess(4_242L, Instant.parse("2026-11-15T18:00:00Z"));
    private static final ObsProcess RELAUNCHED =
            new ObsProcess(5_120L, Instant.parse("2026-11-15T18:40:00Z"));

    private FakeObs obs;
    private FakeRestarter restarter;
    private MatchRepository matches;
    private RecordingSessionRepository journal;
    private final AtomicLong nanos = new AtomicLong(5_000_000_000_000L);
    private MatchFsm fsm;
    private Path dir;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        this.dir = dir;
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

    /**
     * OBS ends the record output itself (an encode error after a GPU reset, or the user stopping
     * it): it reports the output inactive, remembers the file it closed, and rejects a StopRecord
     * for an output that is no longer running.
     */
    private void obsStoppedTheOutput(String path) {
        obs.recording = false;
        obs.lastStoppedPath = path;
        obs.recordStatus = new MatchFsm.RecordOutputStatus(false, null, null);
        obs.stopFailuresRemaining = 1;
    }

    /**
     * OBS crashed mid-recording and Electron relaunched it: the core is connected to a fresh, idle
     * OBS. No STOPPED ever came (the reconnect reset the events anyway), and StopRecord fails
     * against the new OBS's inactive output. A test that names OBS processes also switches
     * {@code obs.process} to the relaunched one.
     */
    private void obsCrashedAndARelaunchedOneConnected() {
        obs.epoch++;
        obs.recording = false;
        obs.lastStoppedPath = null;
        obs.recordStatus = new MatchFsm.RecordOutputStatus(false, null, null);
        obs.stopFailuresRemaining = 1;
    }

    /**
     * OBS ended the output itself while the websocket was down (a full disk, a stop in the OBS
     * window), and the core then reconnected to that same OBS: no STOPPED was heard, and the
     * reconnect reset the events, so no stop path is known either.
     */
    private void obsEndedTheOutputWhileTheSocketWasDown() {
        obs.epoch++;
        obs.recording = false;
        obs.lastStoppedPath = null;
        obs.recordStatus = new MatchFsm.RecordOutputStatus(false, null, null);
        obs.stopFailuresRemaining = 1;
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
        restarter.succeeds = false; // no restart happened, so the broken verdict stands
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
    void brokenRenderer_logsAndAsksForARestartOncePerProbe_notOnEveryFrame() {
        // ObsHealer refuses (a GPU that stays dead hits its cap), so the verdict stays broken.
        restarter.succeeds = false;
        obs.renderBroken = true;
        Logger fsmLog = (Logger) LoggerFactory.getLogger(MatchFsm.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        fsmLog.addAppender(appender);
        try {
            // Four seconds of the ~10Hz feed: all inside one probe window.
            for (int i = 0; i < 40; i++) {
                fsm.onFrame(playing());
                nanos.addAndGet(100_000_000L);
            }
            assertThat(obs.renderProbes).isEqualTo(1);
            assertThat(restarter.reasons).hasSize(1);

            advanceSeconds(1);
            fsm.onFrame(playing());
            assertThat(obs.renderProbes).isEqualTo(2);
            assertThat(restarter.reasons).as("one restart request per probe").hasSize(2);

            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == Level.WARN)
                    .as("a broken episode warns once; later probes log at debug")
                    .hasSize(1);
            assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
            assertThat(obs.startCalls).isZero();
        } finally {
            fsmLog.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void successfulRestart_letsTheFreshObsBeProbedRightAway() {
        obs.renderBroken = true;
        fsm.onFrame(playing());
        assertThat(restarter.reasons).hasSize(1);

        // Still inside the probe window: the verdict described the OBS that was just ended, so the
        // fresh one is probed instead of waiting out the window on a stale "broken".
        obsRestarted();
        fsm.onFrame(playing());
        assertThat(obs.renderProbes).isEqualTo(2);
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
        assertThat(rows).extracting(MatchSummary::dotaMatchId)
                .as("both parts keep the match link, so both are enriched")
                .containsOnly(MATCH);
    }

    @Test
    void outputObsStoppedOnItsOwn_withABrokenRenderer_keepsTheFile_andRecordsTheRest() {
        fsm.onFrame(playing());
        advanceSeconds(300);
        obsStoppedTheOutput("C:\\videos\\part-1.mkv");
        obs.renderBroken = true;
        fsm.superviseRecording();

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        assertThat(restarter.reasons).hasSize(1);
        assertThat(matches.findAll()).as("the part OBS recorded stays attached").singleElement()
                .satisfies(r -> assertThat(r.videoPath()).isEqualTo("C:\\videos\\part-1.mkv"));

        obsRestarted();
        fsm.onFrame(playing());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
        advanceSeconds(600);
        fsm.onFrame(frame().state(POST_GAME).matchId(MATCH).mono(nanos.get()).build());
        assertThat(matches.findAll()).hasSize(2)
                .extracting(MatchSummary::dotaMatchId).containsOnly(MATCH);
    }

    @Test
    void obsRelaunchedMidMatch_keepsThePartItRecorded_andRecordsTheRestOnTheNewObs() {
        obs.startedPath = "C:\\videos\\part-1.mkv";
        fsm.onFrame(playing());
        advanceSeconds(300);
        obsCrashedAndARelaunchedOneConnected();
        fsm.superviseRecording();

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        assertThat(restarter.reasons).as("the relaunched OBS is fresh; no restart").isEmpty();
        assertThat(matches.findAll()).as("the crashed OBS's part keeps its file").singleElement()
                .satisfies(r -> assertThat(r.videoPath()).isEqualTo("C:\\videos\\part-1.mkv"));

        obs.recordStatus = null;
        fsm.onFrame(playing());
        assertThat(fsm.getState()).as("the next in-match frame records on the new OBS")
                .isEqualTo(MatchState.RECORDING);
        advanceSeconds(600);
        fsm.onFrame(frame().state(POST_GAME).matchId(MATCH).mono(nanos.get()).build());
        assertThat(matches.findAll()).hasSize(2)
                .extracting(MatchSummary::dotaMatchId).containsOnly(MATCH);
    }

    @Test
    void obsRelaunchedMidMatch_withNoStartPath_stillRecordsTheRest() {
        // The STARTED event named no file, as an older obs-websocket's does (the bundled one always
        // names it): the part is kept as a row without video, the boot orphan scan adopts its file,
        // and the rest of the match still records.
        fsm.onFrame(playing());
        advanceSeconds(300);
        obsCrashedAndARelaunchedOneConnected();
        fsm.superviseRecording();

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        assertThat(matches.findAll()).singleElement()
                .satisfies(r -> assertThat(r.videoPath()).isNull());

        obs.recordStatus = null;
        fsm.onFrame(playing());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
    }

    @Test
    void aReconnectToAnObsThatKeptRecording_leavesTheRecordingAlone() {
        // A websocket blip: the same OBS, still recording, on a new connection.
        fsm.onFrame(playing());
        advanceSeconds(300);
        obs.epoch++;
        obs.recordStatus = new MatchFsm.RecordOutputStatus(true, 300_000L, 50_000_000L);
        fsm.superviseRecording();

        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
        assertThat(matches.findAll()).isEmpty();
    }

    @Test
    void aRelaunchLandingDuringTheProbe_isStillCaught_byTheConnectionCount() {
        // The old OBS answers "still recording" just before it dies, and its relaunch's connection
        // lands while that answer is in flight. Read before the probe, the connection the output is
        // credited to stays the old one, so the relaunched OBS's idle output reads as a relaunch on
        // the next tick. Read after it, the rest of the match went unrecorded.
        fsm.onFrame(playing());
        advanceSeconds(300);
        obs.recordStatus = new MatchFsm.RecordOutputStatus(true, 300_000L, 50_000_000L);
        obs.duringProbe = this::obsCrashedAndARelaunchedOneConnected;
        fsm.superviseRecording();
        obs.duringProbe = null;

        advanceSeconds(5);
        fsm.superviseRecording();

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
    }

    @Test
    void aRelaunchLandingDuringTheProbe_isStillCaught_byTheObsProcess() {
        obs.process = OBS_RUN;
        fsm.onFrame(playing());
        advanceSeconds(300);
        obs.recordStatus = new MatchFsm.RecordOutputStatus(true, 300_000L, 50_000_000L);
        obs.duringProbe = () -> {
            obsCrashedAndARelaunchedOneConnected();
            obs.exited.add(OBS_RUN);
            obs.process = RELAUNCHED;
        };
        fsm.superviseRecording();
        obs.duringProbe = null;

        advanceSeconds(5);
        fsm.superviseRecording();

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
    }

    @Test
    void aStopOnTheConnectionABlipMoved_isLeftForPostGame() {
        // A websocket blip moves the still-recording output onto a new connection; later OBS stops
        // it on that same connection while still rendering (a full disk, a stop in the OBS window).
        // That is no relaunch: re-arming against it would only loop junk rows (or, on a full disk,
        // fail the start and restart OBS mid-match).
        fsm.onFrame(playing());
        advanceSeconds(300);
        obs.epoch++;
        obs.recordStatus = new MatchFsm.RecordOutputStatus(true, 300_000L, 50_000_000L);
        fsm.superviseRecording();

        advanceSeconds(60);
        obsStoppedTheOutput("C:\\videos\\stopped-after-blip.mkv");
        fsm.superviseRecording();

        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
        assertThat(restarter.reasons).isEmpty();

        fsm.onFrame(frame().state(POST_GAME).matchId(MATCH).mono(nanos.get()).build());
        assertThat(matches.findAll()).singleElement()
                .satisfies(r -> assertThat(r.videoPath())
                        .isEqualTo("C:\\videos\\stopped-after-blip.mkv"));
    }

    @Test
    void aStopObsMadeItself_staysItsOwn_afterAReconnectToTheSameObs() {
        // The disk fills: OBS ends the output, keeps rendering, and the stop is left for POST_GAME.
        // Minutes later the websocket drops and the core reconnects to that same OBS. Read as a
        // relaunch, the match was cut there and re-armed against the full disk.
        obs.process = OBS_RUN;
        obs.startedPath = "C:\\videos\\disk-full.mkv";
        fsm.onFrame(playing());
        advanceSeconds(300);
        obsStoppedTheOutput("C:\\videos\\disk-full.mkv");
        fsm.superviseRecording();
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);

        advanceSeconds(120);
        obs.epoch++; // reconnected to the same OBS
        obs.lastStoppedPath = null; // and the reconnect reset the events
        fsm.superviseRecording();

        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
        assertThat(restarter.reasons).isEmpty();
        fsm.onFrame(frame().state(POST_GAME).matchId(MATCH).mono(nanos.get()).build());
        assertThat(matches.findAll()).singleElement()
                .satisfies(r -> assertThat(r.videoPath()).isEqualTo("C:\\videos\\disk-full.mkv"));
    }

    @Test
    void aStopObsMadeWhileTheSocketWasDown_isItsOwn_whenTheSameObsComesBack() {
        obs.process = OBS_RUN;
        obs.startedPath = "C:\\videos\\stopped-offline.mkv";
        fsm.onFrame(playing());
        advanceSeconds(300);
        obsEndedTheOutputWhileTheSocketWasDown();
        fsm.superviseRecording();

        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
        assertThat(restarter.reasons).isEmpty();
        fsm.onFrame(frame().state(POST_GAME).matchId(MATCH).mono(nanos.get()).build());
        assertThat(matches.findAll()).singleElement()
                .satisfies(r -> assertThat(r.videoPath())
                        .isEqualTo("C:\\videos\\stopped-offline.mkv"));
    }

    @Test
    void aStopObsMadeWhileTheSocketWasDown_isItsOwn_whenTheReconnectCantNameAnObsThatStillRuns() {
        obs.process = OBS_RUN;
        fsm.onFrame(playing());
        advanceSeconds(300);
        obsEndedTheOutputWhileTheSocketWasDown();
        obs.process = null; // the reconnect couldn't name it, but the recording's OBS still runs
        fsm.superviseRecording();

        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
    }

    @Test
    void obsRelaunchedMidMatch_isToldByTheProcessNowConnected() {
        obs.process = OBS_RUN;
        obs.startedPath = "C:\\videos\\part-1.mkv";
        fsm.onFrame(playing());
        advanceSeconds(300);
        obsCrashedAndARelaunchedOneConnected();
        obs.process = RELAUNCHED;
        fsm.superviseRecording();

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        assertThat(restarter.reasons).as("the relaunched OBS is fresh; no restart").isEmpty();
        assertThat(matches.findAll()).singleElement()
                .satisfies(r -> assertThat(r.videoPath()).isEqualTo("C:\\videos\\part-1.mkv"));
    }

    @Test
    void obsRelaunchedMidMatch_whoseProcessCantBeNamed_isToldByTheOldOneBeingGone() {
        obs.process = OBS_RUN;
        fsm.onFrame(playing());
        advanceSeconds(300);
        obsCrashedAndARelaunchedOneConnected();
        obs.exited.add(OBS_RUN);
        obs.process = null;
        fsm.superviseRecording();

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
    }

    @Test
    void anOutputSeenRunningOnANamedObs_belongsToThatObsFromThenOn() {
        // The start couldn't name its OBS, so only the connection count tells a relaunch. Once a
        // connection that names its OBS finds the output still running, that OBS holds it, and a
        // stop it makes across a later reconnect to it is its own.
        fsm.onFrame(playing());
        advanceSeconds(300);
        obs.epoch++;
        obs.process = OBS_RUN;
        obs.recordStatus = new MatchFsm.RecordOutputStatus(true, 300_000L, 50_000_000L);
        fsm.superviseRecording();

        advanceSeconds(60);
        obsEndedTheOutputWhileTheSocketWasDown();
        fsm.superviseRecording();

        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
    }

    @Test
    void outputStoppedWhileObsStillRenders_isLeftForPostGame() {
        // The user stopped it in the OBS window, or the disk filled: a restart can't fix either,
        // and re-arming against them would only loop junk rows.
        fsm.onFrame(playing());
        advanceSeconds(300);
        obsStoppedTheOutput("C:\\videos\\stopped-by-user.mkv");
        fsm.superviseRecording();

        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
        assertThat(restarter.reasons).isEmpty();

        fsm.onFrame(frame().state(POST_GAME).matchId(MATCH).mono(nanos.get()).build());
        assertThat(matches.findAll()).singleElement()
                .satisfies(r -> assertThat(r.videoPath()).isEqualTo("C:\\videos\\stopped-by-user.mkv"));
    }

    @Test
    void emptyRecording_thatObsStoppedFirst_isStillCleanedUp() throws Exception {
        Path empty = Files.createFile(dir.resolve("empty.mkv"));
        fsm.onFrame(playing());
        advanceSeconds(12);
        obs.recordStatus = new MatchFsm.RecordOutputStatus(true, 12_000L, 0L);
        obs.renderBroken = true;
        // OBS ends the output between the status probe and the discard's StopRecord.
        obs.stopFailuresRemaining = 1;
        obs.lastStoppedPath = empty.toString();
        fsm.superviseRecording();

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        assertThat(matches.findAll()).isEmpty();
        assertThat(empty).doesNotExist();
    }

    @Test
    void discard_neverDeletesAFileWithBytesInIt() throws Exception {
        Path written = Files.writeString(dir.resolve("written.mkv"), "frames");
        fsm.onFrame(playing());
        advanceSeconds(12);
        obs.recordStatus = new MatchFsm.RecordOutputStatus(true, 12_000L, 0L);
        obs.renderBroken = true;
        obs.stopFailuresRemaining = 1;
        obs.lastStoppedPath = written.toString();
        fsm.superviseRecording();

        assertThat(written).exists().hasContent("frames");
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
