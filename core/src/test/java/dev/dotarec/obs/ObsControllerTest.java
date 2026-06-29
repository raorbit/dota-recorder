package dev.dotarec.obs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dotarec.config.SettingsStore;
import dev.dotarec.config.SettingsStore.AudioSource;
import io.obswebsocket.community.client.OBSRemoteController;
import io.obswebsocket.community.client.message.response.inputs.GetInputListResponse;
import io.obswebsocket.community.client.message.response.record.GetRecordStatusResponse;
import io.obswebsocket.community.client.message.response.record.StartRecordResponse;
import io.obswebsocket.community.client.message.response.record.StopRecordResponse;
import io.obswebsocket.community.client.message.response.scenes.GetCurrentProgramSceneResponse;
import java.util.List;
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
    void isReady_withEmptyAudioSources_doesNotRequireAudioInput() {
        // A user who cleared every audio source wants a video-only recording. isReady() must NOT
        // demand an audio input there, or it would be permanently false and disable all recording.
        ObsHealth health = new ObsHealth();
        SettingsStore settings = mock(SettingsStore.class);
        SettingsStore.Settings s = new SettingsStore.Settings();
        s.audioSources = List.of(); // explicitly no audio
        when(settings.get()).thenReturn(s);

        ObsController controller =
                new ObsController(settings, health, new ObsEvents(health), sceneConfigurer());
        OBSRemoteController obs = mock(OBSRemoteController.class);
        GetCurrentProgramSceneResponse scene = mock(GetCurrentProgramSceneResponse.class);
        when(scene.isSuccessful()).thenReturn(true);
        when(scene.getCurrentProgramSceneName()).thenReturn("Dota");
        when(obs.getCurrentProgramScene(anyLong())).thenReturn(scene);
        ReflectionTestUtils.setField(controller, "controller", obs);
        health.setConnected(true);

        assertThat(controller.isReady())
                .as("video-only (no audio sources) must still be ready to record")
                .isTrue();
        // The audio existence check must be short-circuited entirely, not just tolerated.
        verify(obs, never()).getInputList(nullable(String.class), anyLong());
    }

    @Test
    void isReady_withConfiguredAudio_stillRequiresALiveAudioInput() {
        // Regression guard: when the user DOES configure audio, the readiness gate still fires so a
        // misconfigured/absent audio input is caught before arming (no silent silent-track recording).
        ObsHealth health = new ObsHealth();
        SettingsStore settings = mock(SettingsStore.class);
        SettingsStore.Settings s = new SettingsStore.Settings();
        s.audioSources = List.of(new AudioSource("a", "application", "::dota2.exe", "Dota", 100, false));
        when(settings.get()).thenReturn(s);

        ObsController controller =
                new ObsController(settings, health, new ObsEvents(health), sceneConfigurer());
        OBSRemoteController obs = mock(OBSRemoteController.class);
        GetCurrentProgramSceneResponse scene = mock(GetCurrentProgramSceneResponse.class);
        when(scene.isSuccessful()).thenReturn(true);
        when(scene.getCurrentProgramSceneName()).thenReturn("Dota");
        when(obs.getCurrentProgramScene(anyLong())).thenReturn(scene);
        // No app-owned audio inputs present yet -> readiness must stay false.
        GetInputListResponse inputs = mock(GetInputListResponse.class);
        when(inputs.isSuccessful()).thenReturn(true);
        when(inputs.getInputs()).thenReturn(List.of());
        when(obs.getInputList(nullable(String.class), anyLong())).thenReturn(inputs);
        ReflectionTestUtils.setField(controller, "controller", obs);
        health.setConnected(true);

        assertThat(controller.isReady())
                .as("configured audio with no live input must NOT be ready")
                .isFalse();
    }

    @Test
    void isReady_withOnlyMutedAudioSource_doesNotRequireAudioInput() {
        // A muted source yields no live audio track (reconcile creates the input but mutes it), so it
        // can never satisfy hasDesktopAudio(). Gating on it would wedge isReady() false forever and
        // silently disable recording — muting your only source should give video-only, not nothing.
        ObsHealth health = new ObsHealth();
        SettingsStore settings = mock(SettingsStore.class);
        SettingsStore.Settings s = new SettingsStore.Settings();
        s.audioSources =
                List.of(new AudioSource("a", "application", "::dota2.exe", "Dota", 100, true)); // muted
        when(settings.get()).thenReturn(s);

        ObsController controller =
                new ObsController(settings, health, new ObsEvents(health), sceneConfigurer());
        OBSRemoteController obs = mock(OBSRemoteController.class);
        GetCurrentProgramSceneResponse scene = mock(GetCurrentProgramSceneResponse.class);
        when(scene.isSuccessful()).thenReturn(true);
        when(scene.getCurrentProgramSceneName()).thenReturn("Dota");
        when(obs.getCurrentProgramScene(anyLong())).thenReturn(scene);
        ReflectionTestUtils.setField(controller, "controller", obs);
        health.setConnected(true);

        assertThat(controller.isReady())
                .as("a muted-only audio config must record video-only, not be wedged not-ready")
                .isTrue();
        verify(obs, never()).getInputList(nullable(String.class), anyLong());
    }

    @Test
    void isReady_withOnlyIneffectiveAudioSource_doesNotRequireAudioInput() {
        // An application capture with no window picked (blank target) is ineffective: reconcile skips
        // it, so no live input ever exists. The gate must treat it as video-only, not wedge not-ready.
        ObsHealth health = new ObsHealth();
        SettingsStore settings = mock(SettingsStore.class);
        SettingsStore.Settings s = new SettingsStore.Settings();
        s.audioSources =
                List.of(new AudioSource("a", "application", "", "Unpicked", 100, false)); // blank target
        when(settings.get()).thenReturn(s);

        ObsController controller =
                new ObsController(settings, health, new ObsEvents(health), sceneConfigurer());
        OBSRemoteController obs = mock(OBSRemoteController.class);
        GetCurrentProgramSceneResponse scene = mock(GetCurrentProgramSceneResponse.class);
        when(scene.isSuccessful()).thenReturn(true);
        when(scene.getCurrentProgramSceneName()).thenReturn("Dota");
        when(obs.getCurrentProgramScene(anyLong())).thenReturn(scene);
        ReflectionTestUtils.setField(controller, "controller", obs);
        health.setConnected(true);

        assertThat(controller.isReady())
                .as("an ineffective-only audio config must record video-only, not be wedged not-ready")
                .isTrue();
        verify(obs, never()).getInputList(nullable(String.class), anyLong());
    }

    @Test
    void startRecording_holdsTheLockSoAReconnectCannotTearDownTheControllerMidSequence()
            throws Exception {
        // The concurrency fix: startRecording() is synchronized on the same monitor as connect(), so a
        // scheduler reconnect tick cannot run disconnectQuietly() (which nulls/replaces the controller)
        // while a start sequence is blocked in awaitRecordConfirmed. We prove mutual exclusion by
        // parking startRecording mid-flight (inside the mocked startRecord, before OUTPUT_STARTED) and
        // showing a concurrent connect() cannot enter until the start completes.
        ObsHealth health = new ObsHealth();
        ObsEvents events = new ObsEvents(health);

        CountDownLatch startInFlight = new CountDownLatch(1); // start has entered startRecord
        CountDownLatch releaseStart = new CountDownLatch(1); // let start confirm + return

        SettingsStore settings = mock(SettingsStore.class);
        when(settings.get()).thenReturn(new SettingsStore.Settings());

        // connect() rebuilds via buildController; capture whether it ran (it must NOT until start ends).
        java.util.concurrent.atomic.AtomicBoolean connectRebuilt =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        ObsController controller =
                new ObsController(settings, health, events, sceneConfigurer()) {
                    @Override
                    OBSRemoteController buildController(SettingsStore.Settings s, CountDownLatch latch) {
                        connectRebuilt.set(true);
                        latch.countDown();
                        return mock(OBSRemoteController.class);
                    }
                };

        OBSRemoteController obs = mock(OBSRemoteController.class);
        StartRecordResponse startOk = mock(StartRecordResponse.class);
        when(startOk.isSuccessful()).thenReturn(true);
        when(obs.startRecord(anyLong()))
                .thenAnswer(
                        inv -> {
                            startInFlight.countDown();
                            // Block here as if waiting on a slow OBS; we hold the controller's lock.
                            releaseStart.await();
                            // Now confirm OUTPUT_STARTED so awaitRecordConfirmed returns.
                            events.onRecordStateChanged("OBS_WEBSOCKET_OUTPUT_STARTED", null);
                            return startOk;
                        });
        ReflectionTestUtils.setField(controller, "controller", obs);
        health.setConnected(true);

        Thread starter = new Thread(controller::startRecording, "starter");
        starter.start();
        assertThat(startInFlight.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // A reconnect tick fires WHILE the start is parked. Because connect() needs the same monitor
        // startRecording holds, this call must block; the rebuild must not happen yet.
        Thread reconnector = new Thread(controller::connect, "reconnector");
        reconnector.start();
        // Give the reconnect thread a moment to (try to) acquire the lock.
        Thread.sleep(100);
        assertThat(connectRebuilt.get())
                .as("connect() must not tear down/rebuild the controller while a start holds the lock")
                .isFalse();

        // Let the start finish; only now may the reconnect proceed.
        releaseStart.countDown();
        starter.join(2_000);
        reconnector.join(2_000);

        assertThat(starter.isAlive()).isFalse();
        assertThat(reconnector.isAlive()).isFalse();
        assertThat(connectRebuilt.get())
                .as("connect() runs once the start releases the lock")
                .isTrue();
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

    @Test
    void refreshRecordingActive_resyncsHealthFromLiveObsRecordState() {
        // A websocket blip fired onConnectionLost(), optimistically clearing health.recording -- but OBS
        // kept recording across the drop. On reconnect, refreshRecordingActive must re-sync the flag from
        // OBS's live GetRecordStatus so the next match's corrective StopRecord fires instead of OBS
        // rejecting StartRecord ("output already active").
        ObsHealth health = new ObsHealth();
        ObsController controller =
                new ObsController(null, health, new ObsEvents(health), sceneConfigurer());
        OBSRemoteController obs = mock(OBSRemoteController.class);
        GetRecordStatusResponse status = mock(GetRecordStatusResponse.class);
        when(status.isSuccessful()).thenReturn(true);
        when(status.getOutputActive()).thenReturn(true);
        when(obs.getRecordStatus(anyLong())).thenReturn(status);
        ReflectionTestUtils.setField(controller, "controller", obs);

        health.setRecording(false); // the optimistic clear from the disconnect

        ReflectionTestUtils.invokeMethod(controller, "refreshRecordingActive");

        assertThat(health.isRecording())
                .as("a reconnect must re-sync recording=true from live OBS so the next corrective stop fires")
                .isTrue();
    }

    @Test
    void refreshRecordingActive_isBestEffort_leavesFlagUnchangedOnFailedStatus() {
        // Best-effort: a null/failed GetRecordStatus (a wedged OBS at the connect's status probe) must
        // not clobber the flag -- the corrective StopRecord guard remains the backstop.
        ObsHealth health = new ObsHealth();
        ObsController controller =
                new ObsController(null, health, new ObsEvents(health), sceneConfigurer());
        OBSRemoteController obs = mock(OBSRemoteController.class);
        when(obs.getRecordStatus(anyLong())).thenReturn(null);
        ReflectionTestUtils.setField(controller, "controller", obs);

        health.setRecording(true);

        ReflectionTestUtils.invokeMethod(controller, "refreshRecordingActive");

        assertThat(health.isRecording())
                .as("a failed status answer must leave health.recording as-is")
                .isTrue();
    }
}
