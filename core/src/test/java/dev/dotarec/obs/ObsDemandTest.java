package dev.dotarec.obs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.dotarec.fsm.MatchFsm;
import dev.dotarec.fsm.MatchState;
import dev.dotarec.gsi.GsiHeartbeat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ObsDemandTest {

    private static final long MS = 1_000_000L;

    private final GsiHeartbeat gsi = new GsiHeartbeat();
    private final ObsHealth health = new ObsHealth();
    private final MatchFsm fsm = mock(MatchFsm.class);
    private final AtomicBoolean gameRunning = new AtomicBoolean();
    private final AtomicInteger scans = new AtomicInteger();
    private final long[] now = {1_000_000 * MS};
    private ObsDemand demand;

    @BeforeEach
    void setUp() {
        when(fsm.getState()).thenReturn(MatchState.IDLE);
        demand =
                new ObsDemand(
                        gsi,
                        health,
                        fsm,
                        () -> {
                            scans.incrementAndGet();
                            return gameRunning.get();
                        },
                        () -> now[0]);
    }

    @Test
    void idleWithNoGameIsNotWanted() {
        assertThat(demand.isWanted()).isFalse();
    }

    @Test
    void wantedWhileTheGameProcessRuns() {
        gameRunning.set(true);

        assertThat(demand.isWanted()).isTrue();
    }

    @Test
    void processScanIsCachedBetweenPolls() {
        demand.isWanted();
        demand.isWanted();
        assertThat(scans.get()).isEqualTo(1);

        now[0] += ObsDemand.PROCESS_SCAN_CACHE_MS * MS;
        gameRunning.set(true);
        assertThat(demand.isWanted()).isTrue();
        assertThat(scans.get()).isEqualTo(2);
    }

    @Test
    void wantedWhileARecentAuthorizedGsiFrameSaysDotaIsUp() {
        gsi.markAuthorizedAtNanos(System.nanoTime());

        assertThat(demand.isWanted()).isTrue();
    }

    @Test
    void anOldGsiFrameDoesNotKeepObsUp() {
        gsi.markAuthorizedAtNanos(System.nanoTime() - (ObsDemand.GSI_RECENT_MS + 1_000) * MS);

        assertThat(demand.isWanted()).isFalse();
    }

    @Test
    void wantedWhileAMatchArmsOrRecords() {
        when(fsm.getState()).thenReturn(MatchState.ARMED);

        assertThat(demand.isWanted()).isTrue();
    }

    @Test
    void wantedWhileObsHasAnActiveOutput() {
        health.setRecording(true);

        assertThat(demand.isWanted()).isTrue();
    }

    @Test
    void wantedWhileAStopFailedOutputIsBeingRetried() {
        when(fsm.hasPendingOrphanedStop()).thenReturn(true);

        assertThat(demand.isWanted()).isTrue();
    }

    @Test
    void aTouchKeepsObsWantedForTheLease() {
        demand.touch();
        now[0] += (ObsDemand.LEASE_MS - 1) * MS;
        assertThat(demand.isWanted()).isTrue();

        now[0] += 1 * MS;
        assertThat(demand.isWanted()).isFalse();
    }

    @Test
    void isGameExe_matchesTheExecutableNameOnly() {
        assertThat(ObsDemand.isGameExe("C:\\Steam\\steamapps\\common\\dota 2 beta\\game\\bin\\win64\\dota2.exe"))
                .isTrue();
        assertThat(ObsDemand.isGameExe("C:\\games\\DOTA2.EXE")).isTrue();
        assertThat(ObsDemand.isGameExe("C:\\tools\\notdota2.exe")).isFalse();
        assertThat(ObsDemand.isGameExe("C:\\dota2.exe.bak")).isFalse();
        assertThat(ObsDemand.isGameExe("")).isFalse();
        assertThat(ObsDemand.isGameExe(null)).isFalse();
    }
}
