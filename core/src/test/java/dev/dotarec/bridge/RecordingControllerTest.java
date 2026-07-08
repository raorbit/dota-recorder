package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dotarec.bridge.RecordingController.StopResult;
import dev.dotarec.fsm.MatchFsm;
import dev.dotarec.fsm.MatchState;
import org.junit.jupiter.api.Test;

/**
 * Locks the {@code POST /recording/stop} contract: it always force-finalizes (idempotent) and pushes
 * a fresh status frame, and reports whether a recording was actually in flight when the call landed.
 */
class RecordingControllerTest {

    @Test
    void stop_whileRecording_forceFinalizesPushesStatusAndReportsTrue() {
        MatchFsm fsm = mock(MatchFsm.class);
        EventPublisher events = mock(EventPublisher.class);
        when(fsm.getState()).thenReturn(MatchState.RECORDING);

        StopResult result = new RecordingController(fsm, events).stop();

        assertThat(result.wasRecording()).isTrue();
        verify(fsm).forceFinalize();
        verify(events).publishStatus();
    }

    @Test
    void stop_whileIdle_stillForceFinalizesButReportsFalse() {
        MatchFsm fsm = mock(MatchFsm.class);
        EventPublisher events = mock(EventPublisher.class);
        when(fsm.getState()).thenReturn(MatchState.IDLE);

        StopResult result = new RecordingController(fsm, events).stop();

        // forceFinalize is idempotent (a no-op off RECORDING); the controller calls it unconditionally
        // and the flag only tells the UI there was nothing to stop.
        assertThat(result.wasRecording()).isFalse();
        verify(fsm).forceFinalize();
        verify(events).publishStatus();
    }

    @Test
    void stop_whenObsHoldsAnOrphanedOutput_stopsItAndReportsTrue() {
        // The FSM-orphaned state: a finalize's double StopRecord failed, the FSM reset to IDLE, and
        // OBS kept writing. The status card (driven by ObsHealth) still shows "Recording", so the stop
        // button is visible — the endpoint must actually stop that output and report "stopped", not
        // no-op through forceFinalize and claim nothing was recording.
        MatchFsm fsm = mock(MatchFsm.class);
        EventPublisher events = mock(EventPublisher.class);
        when(fsm.getState()).thenReturn(MatchState.IDLE);
        when(fsm.stopOrphanedRecording()).thenReturn(true);

        StopResult result = new RecordingController(fsm, events).stop();

        assertThat(result.wasRecording()).isTrue();
        verify(fsm).forceFinalize();
        verify(fsm).stopOrphanedRecording();
        verify(events).publishStatus();
    }

    @Test
    void stop_whenStatusPushThrows_swallowsAndStillReturns() {
        MatchFsm fsm = mock(MatchFsm.class);
        EventPublisher events = mock(EventPublisher.class);
        when(fsm.getState()).thenReturn(MatchState.RECORDING);
        doThrow(new RuntimeException("ws down")).when(events).publishStatus();

        StopResult result = new RecordingController(fsm, events).stop();

        assertThat(result.wasRecording()).isTrue();
        verify(fsm).forceFinalize();
    }
}
