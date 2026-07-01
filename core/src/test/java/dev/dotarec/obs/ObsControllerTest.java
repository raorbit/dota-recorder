package dev.dotarec.obs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import io.obswebsocket.community.client.message.response.inputs.GetInputMuteResponse;
import io.obswebsocket.community.client.message.response.record.GetRecordStatusResponse;
import io.obswebsocket.community.client.message.response.record.StartRecordResponse;
import io.obswebsocket.community.client.message.response.record.StopRecordResponse;
import io.obswebsocket.community.client.message.response.scenes.GetCurrentProgramSceneResponse;
import io.obswebsocket.community.client.model.Input;
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
    void isReady_withMutedOwnedInputBeforeLiveOwnedInput_isReadyRegardlessOfEnumerationOrder() {
        // Regression guard (PR #47): hasDesktopAudio must scan ALL app-owned inputs and pass when ANY
        // is live, not probe only the FIRST enumerated one. getInputList's order is unspecified, so on
        // the default fresh-install config (Dota unmuted, mic + desktop MUTED) a first-only check would
        // return false whenever a muted owned input sorted ahead of the unmuted one -> isReady() false
        // -> MatchFsm never arms -> recording silently disabled on a valid config. Here the MUTED owned
        // input is listed BEFORE the UNMUTED one; readiness must still be true.
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

        long readinessTimeoutMs =
                (long) ReflectionTestUtils.getField(ObsController.class, "READINESS_TIMEOUT_MS");

        // Two app-owned inputs: the muted one enumerated FIRST, the live (unmuted) one SECOND.
        String mutedName = ObsSceneConfigurer.OWNED_PREFIX + "desktop";
        String liveName = ObsSceneConfigurer.OWNED_PREFIX + "dota";
        Input muted = mock(Input.class);
        when(muted.getInputName()).thenReturn(mutedName);
        Input live = mock(Input.class);
        when(live.getInputName()).thenReturn(liveName);
        GetInputListResponse inputs = mock(GetInputListResponse.class);
        when(inputs.isSuccessful()).thenReturn(true);
        when(inputs.getInputs()).thenReturn(List.of(muted, live));
        when(obs.getInputList(nullable(String.class), anyLong())).thenReturn(inputs);

        GetInputMuteResponse mutedResp = mock(GetInputMuteResponse.class);
        when(mutedResp.isSuccessful()).thenReturn(true);
        when(mutedResp.getInputMuted()).thenReturn(true);
        when(obs.getInputMute(eq(mutedName), eq(readinessTimeoutMs))).thenReturn(mutedResp);
        GetInputMuteResponse liveResp = mock(GetInputMuteResponse.class);
        when(liveResp.isSuccessful()).thenReturn(true);
        when(liveResp.getInputMuted()).thenReturn(false);
        when(obs.getInputMute(eq(liveName), eq(readinessTimeoutMs))).thenReturn(liveResp);

        ReflectionTestUtils.setField(controller, "controller", obs);
        health.setConnected(true);

        assertThat(controller.isReady())
                .as("a live owned input enumerated after a muted one must still make isReady() true")
                .isTrue();
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
        // On reconnect, refreshRecordingActive must re-sync health.recording from OBS's live
        // GetRecordStatus so the flag reflects reality (whether OBS kept recording across the blip or
        // stopped) and the next match's corrective StopRecord fires when appropriate instead of OBS
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

    @Test
    void isReady_probesUseTheShortReadinessTimeoutNotTheFullRequestTimeout() {
        // Finding 1: isReady() runs on the GSI thread under the MatchFsm lock. Against a
        // connected-but-unresponsive OBS, using the 5s REQUEST_TIMEOUT_MS per chained call (scene +
        // input list + one mute) would block 10-25s with that lock held, stalling arm/finalize and the
        // watchdog. Prove the probes are issued with the ~1s READINESS_TIMEOUT_MS instead. Asserting the
        // per-call timeout is the robust check (a wall-clock bound would only measure the mock and flake
        // on a loaded CI box).
        ObsHealth health = new ObsHealth();
        SettingsStore settings = mock(SettingsStore.class);
        SettingsStore.Settings s = new SettingsStore.Settings();
        s.audioSources =
                List.of(new AudioSource("a", "application", "::dota2.exe", "Dota", 100, false));
        when(settings.get()).thenReturn(s);

        ObsController controller =
                new ObsController(settings, health, new ObsEvents(health), sceneConfigurer());
        OBSRemoteController obs = mock(OBSRemoteController.class);
        GetCurrentProgramSceneResponse scene = mock(GetCurrentProgramSceneResponse.class);
        when(scene.isSuccessful()).thenReturn(true);
        when(scene.getCurrentProgramSceneName()).thenReturn("Dota");
        when(obs.getCurrentProgramScene(anyLong())).thenReturn(scene);
        GetInputListResponse inputs = mock(GetInputListResponse.class);
        when(inputs.isSuccessful()).thenReturn(true);
        when(inputs.getInputs()).thenReturn(List.of());
        when(obs.getInputList(nullable(String.class), anyLong())).thenReturn(inputs);
        ReflectionTestUtils.setField(controller, "controller", obs);
        health.setConnected(true);

        controller.isReady();

        long readinessTimeoutMs =
                (long)
                        ReflectionTestUtils.getField(
                                ObsController.class, "READINESS_TIMEOUT_MS");
        long requestTimeoutMs =
                (long) ReflectionTestUtils.getField(ObsController.class, "REQUEST_TIMEOUT_MS");
        assertThat(readinessTimeoutMs)
                .as("readiness probes must use a much shorter budget than the 5s request timeout")
                .isLessThan(requestTimeoutMs);
        // Both probes must be issued with the short readiness timeout, never the 5s request timeout.
        verify(obs).getCurrentProgramScene(readinessTimeoutMs);
        verify(obs).getCurrentProgramScene(anyLong()); // exactly once, at the readiness timeout
        verify(obs).getInputList(nullable(String.class), eq(readinessTimeoutMs));
        verify(obs, never()).getCurrentProgramScene(requestTimeoutMs);
        verify(obs, never()).getInputList(nullable(String.class), eq(requestTimeoutMs));
    }

    @Test
    void isReady_shortCircuitsOnNoActiveSceneWithoutProbingAudio() {
        // Finding 1: no active program scene -> not ready, and we must NOT go on to probe audio (a
        // second readiness timeout against a wedged OBS). Short-circuiting caps the wall time spent
        // holding the FSM lock.
        ObsHealth health = new ObsHealth();
        SettingsStore settings = mock(SettingsStore.class);
        SettingsStore.Settings s = new SettingsStore.Settings();
        s.audioSources =
                List.of(new AudioSource("a", "application", "::dota2.exe", "Dota", 100, false));
        when(settings.get()).thenReturn(s);

        ObsController controller =
                new ObsController(settings, health, new ObsEvents(health), sceneConfigurer());
        OBSRemoteController obs = mock(OBSRemoteController.class);
        GetCurrentProgramSceneResponse scene = mock(GetCurrentProgramSceneResponse.class);
        when(scene.isSuccessful()).thenReturn(true);
        when(scene.getCurrentProgramSceneName()).thenReturn(""); // no active scene
        when(obs.getCurrentProgramScene(anyLong())).thenReturn(scene);
        ReflectionTestUtils.setField(controller, "controller", obs);
        health.setConnected(true);

        assertThat(controller.isReady())
                .as("no active program scene must report not-ready")
                .isFalse();
        // Audio must not be probed at all once the scene check fails.
        verify(obs, never()).getInputList(nullable(String.class), anyLong());
    }

    @Test
    void onConnectionLost_leavesRecordingFlagUnchanged_clearsOnlyConnectedAndScene() {
        // Finding 2: a transient websocket drop does NOT stop OBS -- it keeps recording. Clearing
        // health.recording optimistically here opens a window where a finalize sees stopRecording()
        // throw and then isRecording()==false, so stopRecordingWithRetry() skips its still-recording
        // retry and persists the match with video_path=null. onConnectionLost must leave recording as-is
        // (refreshRecordingActive re-syncs it on reconnect) while still clearing connected + sceneActive.
        ObsHealth health = new ObsHealth();
        ObsController controller =
                new ObsController(null, health, new ObsEvents(health), sceneConfigurer());

        health.setConnected(true);
        health.setSceneActive(true);
        health.setRecording(true); // OBS is mid-recording when the socket drops

        ReflectionTestUtils.invokeMethod(controller, "onConnectionLost");

        assertThat(health.isRecording())
                .as("a transient drop must NOT clear recording -- OBS keeps recording; keeps the retry armed")
                .isTrue();
        assertThat(health.isConnected())
                .as("connection-lost must still clear connected")
                .isFalse();
        assertThat(health.isSceneActive())
                .as("connection-lost must still clear sceneActive")
                .isFalse();
    }
}
