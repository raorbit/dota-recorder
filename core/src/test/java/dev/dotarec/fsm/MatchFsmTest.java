package dev.dotarec.fsm;

import static dev.dotarec.gsi.GsiFrames.frame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dotarec.bridge.EventPublisher;
import dev.dotarec.data.MarkerRepository;
import dev.dotarec.data.MarkerRow;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchSummary;
import dev.dotarec.data.PauseRepository;
import dev.dotarec.data.TestDb;
import dev.dotarec.gsi.GsiFrame;
import dev.dotarec.obs.ObsException;
import dev.dotarec.obs.ObsRecorder;
import dev.dotarec.obs.ThumbnailCapturer;
import dev.dotarec.tagger.EventTagger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the FSM through start / tag / finalize against a fake OBS + fake thumbnail capturer and a
 * REAL temp SQLite DB (via {@link TestDb}, the same migration path production uses). Proves:
 * GAME_IN_PROGRESS is a valid entry state, the start is idempotent across duplicate frames, unknown
 * states and menu activity never start, and POST_GAME stops + persists exactly one match row with
 * its buffered markers (thumbnail captured BEFORE stop).
 */
class MatchFsmTest {

    /** Fake OBS the FSM drives deterministically; confirms synchronously like the seam test fake. */
    static final class FakeObs implements ObsRecorder {
        boolean connected = true;
        boolean ready = true;
        boolean stopThrowsRuntime = false;
        Instant confirmedAt;
        int startCalls;
        int stopCalls;
        long thenStopCalledAt = -1;
        String savedPath = "C:\\videos\\match.mkv";

        @Override public void connect() { }
        @Override public boolean ensureConnected() { return connected; }
        @Override public boolean isReady() { return connected && ready; }

        @Override public String startRecording() {
            if (!connected) {
                throw new ObsException("OBS is not connected");
            }
            startCalls++;
            confirmedAt = Instant.now();
            return confirmedAt.toString();
        }

        @Override public String stopRecording() {
            if (!connected) {
                throw new ObsException("OBS is not connected");
            }
            stopCalls++;
            if (stopThrowsRuntime) {
                // A non-ObsException library failure (socket reset, internal NPE/ISE, etc.).
                throw new IllegalStateException("obs-websocket library failure");
            }
            thenStopCalledAt = System.nanoTime();
            return savedPath;
        }

        @Override public Instant recordConfirmedAt() { return confirmedAt; }
    }

    /** Fake thumbnail capturer; records the order vs stopRecording to prove thumbnail-before-stop. */
    static final class FakeThumbs implements ThumbnailCapturer {
        int calls;
        long capturedAt = -1;
        @Override public Path captureCurrentScene(String id) {
            calls++;
            capturedAt = System.nanoTime();
            return Path.of("C:\\videos\\thumbs\\" + id + ".jpg");
        }
    }

    private FakeObs obs;
    private FakeThumbs thumbs;
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
        thumbs = new FakeThumbs();
        fsm = new MatchFsm(obs, thumbs, new EventTagger(), matches, markers, pauses, events, ds);
    }

    @Test
    void gameInProgress_isAValidEntryState_startsRecording() {
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS").activity("playing").build());

        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
        assertThat(obs.startCalls).isEqualTo(1);
    }

    @Test
    void heroSelection_armsAndStartsEarly_evenWithoutPlayerBlock() {
        // Hero-select frame: no player/hero block -> activity null. Early-arm must still start.
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_HERO_SELECTION").noHero().activity(null).build());

        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
        assertThat(obs.startCalls).isEqualTo(1);
    }

    @Test
    void duplicateSameStateFrames_startExactlyOnce() {
        GsiFrame f = frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS").activity("playing").build();
        fsm.onFrame(f);
        fsm.onFrame(f);
        fsm.onFrame(f);

        assertThat(obs.startCalls).as("re-entering RECORDING must never double-start").isEqualTo(1);
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
    }

    @Test
    void unknownState_isANoOp() {
        fsm.onFrame(frame().state("UNKNOWN").build());
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_SOME_FUTURE_STATE").build());

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        assertThat(obs.startCalls).isZero();
    }

    @Test
    void gameInProgressWithMenuActivity_doesNotStart() {
        // Spectating / menu: activity != "playing" -> no recording even in GAME_IN_PROGRESS.
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS").activity("menu").build());

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        assertThat(obs.startCalls).isZero();
    }

    @Test
    void obsDown_staysIdle_andRetriesOnNextFrame() {
        obs.connected = false;
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS").activity("playing").build());
        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        assertThat(obs.startCalls).isZero();

        // OBS comes back; the next frame starts cleanly.
        obs.connected = true;
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS").activity("playing").build());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
        assertThat(obs.startCalls).isEqualTo(1);
    }

    @Test
    void postGame_stops_persistsOneMatchRow_withBufferedMarkers() {
        // Start.
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS").activity("playing")
                .hero("npc_dota_hero_drow_ranger").kills(0).deaths(0).assists(0).build());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);

        // Tag: a kill, then a kill+death tick.
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS").activity("playing")
                .hero("npc_dota_hero_drow_ranger").kills(1).deaths(0).assists(0).alive(true).build());
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS").activity("playing")
                .hero("npc_dota_hero_drow_ranger").kills(2).deaths(1).assists(0).alive(false).build());

        // POST_GAME finalizes.
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_POST_GAME").noHero().build());

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        assertThat(obs.stopCalls).isEqualTo(1);

        // Exactly one match row, carrying the hero/KDA snapshot and the video path from stop.
        List<MatchSummary> rows = matches.findAll();
        assertThat(rows).hasSize(1);
        MatchSummary row = rows.get(0);
        assertThat(row.hero()).isEqualTo("npc_dota_hero_drow_ranger");
        assertThat(row.kills()).isEqualTo(2);
        assertThat(row.deaths()).isEqualTo(1);
        assertThat(row.videoPath()).isEqualTo("C:\\videos\\match.mkv");
        assertThat(row.enrichmentState()).isEqualTo("pending");

        // Buffered markers were flushed: 2 kills, 1 death-counter + 1 falling-edge death = 4.
        List<MarkerRow> persisted = markers.findByMatchId(row.id());
        assertThat(persisted).extracting(MarkerRow::type)
                .containsExactlyInAnyOrder("kill", "kill", "death", "death");

        // Thumbnail captured BEFORE stop (the contract: a post-stop screenshot is black).
        assertThat(thumbs.calls).isEqualTo(1);
        assertThat(thumbs.capturedAt)
                .as("thumbnail must be captured before stopRecording")
                .isLessThan(obs.thenStopCalledAt);

        verify(events).publish(eq("match.recorded"), any());
    }

    @Test
    void thumbnailFailure_doesNotLoseTheRecording() {
        ThumbnailCapturer failing = id -> { throw new ObsException("no scene"); };
        fsm = new MatchFsm(obs, failing, new EventTagger(), matches, markers, pauses, events, ds);

        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS").activity("playing").build());
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_POST_GAME").noHero().build());

        // Row still persisted (without a thumbnail) and recording finalized cleanly.
        assertThat(matches.findAll()).hasSize(1);
        assertThat(matches.findAll().get(0).thumbPath()).isNull();
        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        verify(events, atLeastOnce()).publish(eq("match.recorded"), any());
    }

    @Test
    void forceFinalize_watchdogHook_stopsAnInFlightRecording() {
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS").activity("playing").build());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);

        fsm.forceFinalize();

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        assertThat(obs.stopCalls).isEqualTo(1);
        assertThat(matches.findAll()).hasSize(1);

        // forceFinalize when idle is a harmless no-op.
        fsm.forceFinalize();
        assertThat(matches.findAll()).hasSize(1);
    }

    @Test
    void persistFailureDuringFinalize_doesNotStrandStopping_andCanRecordAgain() throws Exception {
        // A repo that throws on insert simulates a disk-full / constraint failure mid-finalize.
        MatchRepository throwing = mock(MatchRepository.class);
        when(throwing.insert(any(java.sql.Connection.class), any(MatchRepository.NewMatch.class)))
                .thenThrow(new IllegalStateException("disk full"));
        fsm = new MatchFsm(obs, thumbs, new EventTagger(), throwing, markers, pauses, events, ds);

        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS").activity("playing").build());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);

        // POST_GAME finalize: persist throws, but the FSM must return to IDLE, not stick in STOPPING.
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_POST_GAME").noHero().build());
        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        assertThat(obs.stopCalls).isEqualTo(1);

        // A stuck STOPPING would make this next frame a no-op; instead the next match records cleanly.
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS").activity("playing").build());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);
        assertThat(obs.startCalls).isEqualTo(2);
    }

    @Test
    void nonObsExceptionFromStopRecording_stillPersistsRowAndResetsIdle() {
        // A stop failure that isn't a typed ObsException (a socket reset / library RuntimeException)
        // must still fall through to persist the row with a null video path, not abort finalize and
        // strand OBS recording with no row written.
        obs.stopThrowsRuntime = true;

        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS").activity("playing").build());
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_POST_GAME").noHero().build());

        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        assertThat(obs.stopCalls).isEqualTo(1);
        List<MatchSummary> rows = matches.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).videoPath()).isNull();
    }

    @Test
    void concurrentForceFinalizeAndPostGame_finalizesExactlyOnce() throws Exception {
        fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS").activity("playing").build());
        assertThat(fsm.getState()).isEqualTo(MatchState.RECORDING);

        // Race a watchdog forceFinalize() against a normal POST_GAME finalize. Both synchronize on the
        // FSM and flip RECORDING->STOPPING before any side effect, so exactly one stop + one row result
        // no matter which wins (a future "finalize on an executor" refactor must keep this true).
        CyclicBarrier gate = new CyclicBarrier(2);
        java.util.concurrent.atomic.AtomicReference<Throwable> threadError =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread watchdog = new Thread(() -> {
            await(gate);
            fsm.forceFinalize();
        });
        Thread postGame = new Thread(() -> {
            await(gate);
            fsm.onFrame(frame().state("DOTA_GAMERULES_STATE_POST_GAME").noHero().build());
        });
        watchdog.setUncaughtExceptionHandler((t, e) -> threadError.set(e));
        postGame.setUncaughtExceptionHandler((t, e) -> threadError.set(e));
        watchdog.start();
        postGame.start();
        watchdog.join(2_000);
        postGame.join(2_000);

        // The threads must have actually finished (a deadlock under the synchronized monitor would
        // leave one alive past the join timeout) and neither may have thrown -- otherwise the
        // assertions below could pass off the surviving thread alone, masking a regression.
        assertThat(watchdog.isAlive()).as("watchdog thread terminated").isFalse();
        assertThat(postGame.isAlive()).as("postGame thread terminated").isFalse();
        assertThat(threadError.get()).as("neither thread threw").isNull();
        assertThat(fsm.getState()).isEqualTo(MatchState.IDLE);
        assertThat(obs.stopCalls).as("exactly one OBS stop despite the race").isEqualTo(1);
        assertThat(matches.findAll()).hasSize(1);
    }

    private static void await(CyclicBarrier gate) {
        try {
            gate.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
