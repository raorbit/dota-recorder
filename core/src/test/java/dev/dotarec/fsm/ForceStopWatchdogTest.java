package dev.dotarec.fsm;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dotarec.gsi.GsiHeartbeat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Drives {@link ForceStopWatchdog#tick()} against a mock FSM + a real {@link GsiHeartbeat} stamped
 * via its public test-only {@code markAt} hook. Proves the watchdog fires {@code forceFinalize} only
 * when RECORDING AND the last frame is older than the silence threshold -- no clock injection, no
 * {@code Thread.sleep}.
 */
class ForceStopWatchdogTest {

    private MatchFsm fsm;
    private GsiHeartbeat heartbeat;
    private ForceStopWatchdog watchdog;

    @BeforeEach
    void setUp() {
        fsm = Mockito.mock(MatchFsm.class);
        heartbeat = new GsiHeartbeat();
        watchdog = new ForceStopWatchdog(fsm, heartbeat);
    }

    @Test
    void fires_whenRecordingAndStale() {
        when(fsm.getState()).thenReturn(MatchState.RECORDING);
        // Last frame 31s ago: past the 30s threshold.
        heartbeat.markAt(System.currentTimeMillis() - 31_000L);

        watchdog.tick();

        verify(fsm, times(1)).forceFinalize();
    }

    @Test
    void doesNotFire_whenRecordingButFresh() {
        when(fsm.getState()).thenReturn(MatchState.RECORDING);
        // A brief drop (5s) must NOT cut the recording -- threshold is 30s.
        heartbeat.markAt(System.currentTimeMillis() - 5_000L);

        watchdog.tick();

        verify(fsm, never()).forceFinalize();
    }

    @Test
    void doesNotFire_whenNotRecording_evenIfStale() {
        when(fsm.getState()).thenReturn(MatchState.IDLE);
        heartbeat.markAt(System.currentTimeMillis() - 60_000L);

        watchdog.tick();

        verify(fsm, never()).forceFinalize();
    }
}
