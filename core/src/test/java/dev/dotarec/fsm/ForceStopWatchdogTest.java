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
 * via its public test-only {@code markAuthorizedAtNanos} hook (the liveness clock is monotonic, so a
 * wall-clock step can't fabricate a silence -- Finding #6). Proves the watchdog fires
 * {@code forceFinalize} only when RECORDING AND the last AUTHORIZED frame is older than the silence
 * threshold -- no clock injection, no {@code Thread.sleep}.
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
        // Last authorized frame 31s ago: past the 30s threshold.
        heartbeat.markAuthorizedAtNanos(System.nanoTime() - 31_000_000_000L);

        watchdog.tick();

        verify(fsm, times(1)).forceFinalize();
    }

    @Test
    void fires_whenOnlyRawHeartbeatIsFresh_butNoAuthorizedFrame() {
        when(fsm.getState()).thenReturn(MatchState.RECORDING);
        // A flood of spoofed/unauthenticated POSTs keeps the raw heartbeat fresh (mark) but never
        // stamps an authorized frame. The watchdog must ignore the raw heartbeat and still cut.
        heartbeat.mark();

        watchdog.tick();

        verify(fsm, times(1)).forceFinalize();
    }

    @Test
    void doesNotFire_whenRecordingButFresh() {
        when(fsm.getState()).thenReturn(MatchState.RECORDING);
        // A brief drop (5s) must NOT cut the recording -- threshold is 30s.
        heartbeat.markAuthorizedAtNanos(System.nanoTime() - 5_000_000_000L);

        watchdog.tick();

        verify(fsm, never()).forceFinalize();
    }

    @Test
    void doesNotFire_whenFreshAuthorizedFrameViaProductionPath() {
        when(fsm.getState()).thenReturn(MatchState.RECORDING);
        // Exercise the real markAuthorized() path (monotonic nanoTime stamp): a frame just credited
        // the liveness clock, so the watchdog must read ~0ms of silence and stay quiet -- a forward
        // wall-clock step can't inflate this delta because it is a System.nanoTime() difference.
        heartbeat.markAuthorized();

        watchdog.tick();

        verify(fsm, never()).forceFinalize();
    }

    @Test
    void doesNotFire_whenNotRecording_evenIfStale() {
        when(fsm.getState()).thenReturn(MatchState.IDLE);
        heartbeat.markAuthorizedAtNanos(System.nanoTime() - 60_000_000_000L);

        watchdog.tick();

        verify(fsm, never()).forceFinalize();
    }
}
