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
        ObsController controller = new ObsController(null, health, events);
        assertThatThrownBy(controller::startRecording).isInstanceOf(ObsException.class);

        assertThat(events.recordConfirmedAt())
                .as("startRecording must reset the previous match's confirmed-start anchor")
                .isNull();
    }

    @Test
    void startRecording_whenCorrectiveStopTimesOut_keepsRecordingFlagForRetry() {
        ObsHealth health = new ObsHealth();
        ObsController controller = new ObsController(null, health, new ObsEvents(health));

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
        ObsController controller = new ObsController(null, health, new ObsEvents(health));

        OBSRemoteController obs = mock(OBSRemoteController.class);
        StopRecordResponse stopOk = mock(StopRecordResponse.class);
        when(stopOk.isSuccessful()).thenReturn(true);
        when(obs.stopRecord(anyLong())).thenReturn(stopOk);
        StartRecordResponse startOk = mock(StartRecordResponse.class);
        when(startOk.isSuccessful()).thenReturn(true);
        when(obs.startRecord(anyLong())).thenReturn(startOk);
        ReflectionTestUtils.setField(controller, "controller", obs);

        health.setConnected(true);
        health.setRecording(true);

        controller.startRecording();

        // Corrective stop runs BEFORE the real start, and the flag is cleared only on its success.
        InOrder order = inOrder(obs);
        order.verify(obs).stopRecord(anyLong());
        order.verify(obs).startRecord(anyLong());
        assertThat(health.isRecording()).isFalse();
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
                new ObsController(settings, health, new ObsEvents(health)) {
                    @Override
                    OBSRemoteController buildController(SettingsStore.Settings s, CountDownLatch latch) {
                        latch.countDown();
                        return obs;
                    }
                };

        long startNanos = System.nanoTime();
        boolean connected = controller.ensureConnected();
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(connected).isFalse();
        assertThat(elapsedMs)
                .as("a failed connect must return promptly, not wait out the connect timeout")
                .isLessThan(2_000);
        // The crux of the I1 fix: a failure-path latch release must NOT fall through to
        // verifyProtocol(), which would call getVersion() and block on / misdiagnose a dead socket.
        verify(obs, never()).getVersion(anyLong());
    }
}
