package dev.dotarec.obs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dotarec.config.SettingsStore;
import io.obswebsocket.community.client.OBSRemoteController;
import io.obswebsocket.community.client.message.response.record.StartRecordResponse;
import io.obswebsocket.community.client.message.response.record.StopRecordResponse;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit coverage for the parts of {@link ObsController} that don't need a live OBS websocket.
 *
 * <p>The connect/identify handshake needs a real socket, but {@code startRecording}'s pre-flight --
 * the per-recording anchor reset and the corrective StopRecord for a dangling recording -- can be
 * exercised against a mocked {@link OBSRemoteController} injected onto the private field.
 */
class ObsControllerTest {

    /**
     * A scene configurer whose audio reconcile is never driven in these tests (none of them are
     * connected enough to call reconcileAudioOnDemand). A null settings store is fine since no test
     * here exercises the reconcile path.
     */
    private static ObsSceneConfigurer sceneConfigurer() {
        return new ObsSceneConfigurer(mock(SettingsStore.class));
    }

    @Test
    void startRecording_resetsStalePerRecordingAnchorBeforeStarting() {
        ObsHealth health = new ObsHealth();
        ObsEvents events = new ObsEvents(health);
        // Simulate a PRIOR match: started, then stopped. OUTPUT_STOPPED leaves recordConfirmedAt set
        // -- exactly the sticky value that would otherwise become a second match's anchor.
        events.onRecordStateChanged(ObsEvents.OUTPUT_STARTED, null);
        events.onRecordStateChanged(ObsEvents.OUTPUT_STOPPED, "C:\\videos\\match1.mkv");
        assertThat(events.recordConfirmedAt())
                .as("anchor is sticky across STOPPED -- this is why startRecording must reset it")
                .isNotNull();

        // Not connected, so the StartRecord itself fails; but startRecording must FIRST clear the
        // stale anchor so the FSM's read of recordConfirmedAt() falls back to the live frame time.
        ObsController controller = new ObsController(null, health, events, sceneConfigurer());
        assertThatThrownBy(controller::startRecording).isInstanceOf(ObsException.class);

        assertThat(events.recordConfirmedAt())
                .as("startRecording must reset the previous match's confirmed-start anchor")
                .isNull();
    }

    @Test
    void startRecording_whenCorrectiveStopTimesOut_keepsRecordingFlagForRetry() {
        ObsHealth health = new ObsHealth();
        ObsController controller =
                new ObsController(null, health, new ObsEvents(health), sceneConfigurer());

        OBSRemoteController obs = mock(OBSRemoteController.class);
        // A timed-out StopRecord returns null from the library -- it does NOT throw.
        when(obs.stopRecord(anyLong())).thenReturn(null);
        ReflectionTestUtils.setField(controller, "controller", obs);

        health.setConnected(true); // so requireController() passes
        health.setRecording(true); // a dangling prior recording OBS never confirmed stopped

        assertThatThrownBy(controller::startRecording).isInstanceOf(ObsException.class);

        // The regression this guards: a timed-out corrective stop must NOT clear the flag, or every
        // later match would skip the corrective block and silently fail to record forever.
        assertThat(health.isRecording())
                .as("a failed corrective stop must leave recording=true so the next match retries")
                .isTrue();
        // And we must not have issued a StartRecord OBS would reject while still recording.
        verify(obs, never()).startRecord(anyLong());
    }

    @Test
    void startRecording_whenCorrectiveStopSucceeds_clearsFlagThenStarts() {
        ObsHealth health = new ObsHealth();
        ObsEvents events = new ObsEvents(health);
        ObsController controller = new ObsController(null, health, events, sceneConfigurer());

        OBSRemoteController obs = mock(OBSRemoteController.class);
        StopRecordResponse stopOk = mock(StopRecordResponse.class);
        when(stopOk.isSuccessful()).thenReturn(true);
        when(obs.stopRecord(anyLong())).thenReturn(stopOk);
        StartRecordResponse startOk = mock(StartRecordResponse.class);
        when(startOk.isSuccessful()).thenReturn(true);
        // OBS confirms OUTPUT_STARTED on its socket thread right after accepting StartRecord; model
        // that synchronously so the start-confirmation gate (awaitRecordConfirmed) is satisfied.
        when(obs.startRecord(anyLong()))
                .thenAnswer(
                        inv -> {
                            events.onRecordStateChanged("OBS_WEBSOCKET_OUTPUT_STARTED", null);
                            return startOk;
                        });
        ReflectionTestUtils.setField(controller, "controller", obs);

        health.setConnected(true);
        health.setRecording(true);

        controller.startRecording();

        // Corrective stop runs BEFORE the real start; OUTPUT_STARTED then confirms the new recording.
        InOrder order = inOrder(obs);
        order.verify(obs).stopRecord(anyLong());
        order.verify(obs).startRecord(anyLong());
        assertThat(health.isRecording()).isTrue();
    }

    @Test
    void startRecording_whenCorrectiveStopRacesANativeStop_proceedsWithTheNewRecording() {
        ObsHealth health = new ObsHealth();
        ObsEvents events = new ObsEvents(health);
        ObsController controller = new ObsController(null, health, events, sceneConfigurer());

        OBSRemoteController obs = mock(OBSRemoteController.class);
        StopRecordResponse stopFailed = mock(StopRecordResponse.class);
        when(stopFailed.isSuccessful()).thenReturn(false);
        // Model the benign race: OBS's OUTPUT_STOPPED lands DURING the corrective stop -- the recording
        // ends natively (flipping the health flag) and the stop itself reports no active output.
        when(obs.stopRecord(anyLong()))
                .thenAnswer(
                        inv -> {
                            health.setRecording(false);
                            return stopFailed;
                        });
        StartRecordResponse startOk = mock(StartRecordResponse.class);
        when(startOk.isSuccessful()).thenReturn(true);
        // Confirm OUTPUT_STARTED synchronously so the start-confirmation gate is satisfied.
        when(obs.startRecord(anyLong()))
                .thenAnswer(
                        inv -> {
                            events.onRecordStateChanged("OBS_WEBSOCKET_OUTPUT_STARTED", null);
                            return startOk;
                        });
        ReflectionTestUtils.setField(controller, "controller", obs);

        health.setConnected(true);
        health.setRecording(true);

        // Must NOT throw: a failed corrective stop where OBS is no longer recording is a benign race,
        // not the timeout case, so the new recording proceeds instead of being dropped.
        controller.startRecording();

        InOrder order = inOrder(obs);
        order.verify(obs).stopRecord(anyLong());
        order.verify(obs).startRecord(anyLong());
    }

    @Test
    void ensureConnected_whenConnectFailsWithoutReadyEvent_failsFastAndSkipsProtocolCheck() {
        ObsHealth health = new ObsHealth();
        SettingsStore settings = mock(SettingsStore.class);
        when(settings.get()).thenReturn(new SettingsStore.Settings());
        OBSRemoteController obs = mock(OBSRemoteController.class);

        // Simulate a connect that FAILS: the latch is released as onClose/onError would, but the
        // onReady event -- the only thing that sets connectionReady -- never fires.
        ObsController controller =
                new ObsController(settings, health, new ObsEvents(health), sceneConfigurer()) {
                    @Override
                    OBSRemoteController buildController(SettingsStore.Settings s, CountDownLatch latch) {
                        latch.countDown();
                        return obs;
                    }
                };

        boolean connected = controller.ensureConnected();

        assertThat(connected).isFalse();
        // The crux of the I1 fix: a failure-path latch release must NOT fall through to
        // verifyProtocol() -> getVersion(), which is what would block on / misdiagnose a dead socket.
        // Asserting getVersion is never called is the real fail-fast guard (a wall-clock timing bound
        // would only measure the mock, and flakes on a loaded CI box).
        verify(obs, never()).getVersion(anyLong());
    }
}
