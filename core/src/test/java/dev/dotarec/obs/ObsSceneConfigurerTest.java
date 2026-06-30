package dev.dotarec.obs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.config.SettingsStore.AudioSource;
import io.obswebsocket.community.client.OBSRemoteController;
import io.obswebsocket.community.client.message.response.inputs.GetInputListResponse;
import io.obswebsocket.community.client.message.response.inputs.RemoveInputResponse;
import io.obswebsocket.community.client.message.response.inputs.SetInputMuteResponse;
import io.obswebsocket.community.client.model.Input;
import io.obswebsocket.community.client.model.Scene;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the deterministic decision logic of {@link ObsSceneConfigurer} -- the
 * already-exists checks and the pure audio-reconcile helpers (kind mapping, settings JSON, owned-input
 * naming, create-vs-remove diff) that decide whether each setup step creates something or no-ops.
 * These are the parts that make the configurer idempotent; the obs-websocket calls around them need a
 * live OBS and are exercised manually (PR5), not here.
 */
class ObsSceneConfigurerTest {

    private static Scene scene(String name) {
        return new Scene(name, 0);
    }

    private static Input input(String name) {
        Input i = new Input();
        i.setInputName(name);
        return i;
    }

    private static Input input(String name, String kind) {
        Input i = new Input();
        i.setInputName(name);
        i.setInputKind(kind);
        return i;
    }

    private static AudioSource source(String id, String kind, String target) {
        return new AudioSource(id, kind, target, "label", 100, false);
    }

    @Test
    void sceneExists_trueOnlyWhenNamePresent() {
        List<Scene> scenes = List.of(scene("Lobby"), scene(ObsSceneConfigurer.SCENE_NAME));

        assertThat(ObsSceneConfigurer.sceneExists(scenes, ObsSceneConfigurer.SCENE_NAME)).isTrue();
        assertThat(ObsSceneConfigurer.sceneExists(scenes, "Missing")).isFalse();
    }

    @Test
    void sceneExists_nullOrEmptyList_isFalse() {
        assertThat(ObsSceneConfigurer.sceneExists(null, ObsSceneConfigurer.SCENE_NAME)).isFalse();
        assertThat(ObsSceneConfigurer.sceneExists(List.of(), ObsSceneConfigurer.SCENE_NAME))
                .isFalse();
    }

    @Test
    void sceneExists_userPreCreatedScene_isDetected() {
        // Idempotency contract: a user who already made a "Dota" scene must be left alone.
        List<Scene> scenes = List.of(scene(ObsSceneConfigurer.SCENE_NAME));

        assertThat(ObsSceneConfigurer.sceneExists(scenes, ObsSceneConfigurer.SCENE_NAME)).isTrue();
    }

    @Test
    void inputExists_trueOnlyWhenNamePresent() {
        List<Input> inputs = List.of(input(ObsSceneConfigurer.GAME_CAPTURE_INPUT), input("Mic"));

        assertThat(ObsSceneConfigurer.inputExists(inputs, ObsSceneConfigurer.GAME_CAPTURE_INPUT))
                .isTrue();
        assertThat(ObsSceneConfigurer.inputExists(inputs, "Other")).isFalse();
    }

    @Test
    void inputExists_nullOrEmptyList_isFalse() {
        assertThat(ObsSceneConfigurer.inputExists(null, "anything")).isFalse();
        assertThat(ObsSceneConfigurer.inputExists(List.of(), "anything")).isFalse();
    }

    @Test
    void kindToObsKind_mapsTheThreeWasapiKinds() {
        assertThat(ObsSceneConfigurer.kindToObsKind("application"))
                .isEqualTo("wasapi_process_output_capture");
        assertThat(ObsSceneConfigurer.kindToObsKind("output")).isEqualTo("wasapi_output_capture");
        assertThat(ObsSceneConfigurer.kindToObsKind("input")).isEqualTo("wasapi_input_capture");
    }

    @Test
    void kindToObsKind_unknownOrNull_isNull() {
        assertThat(ObsSceneConfigurer.kindToObsKind("bogus")).isNull();
        assertThat(ObsSceneConfigurer.kindToObsKind(null)).isNull();
    }

    @Test
    void ownedInputName_isDotarecPrefixPlusId() {
        assertThat(ObsSceneConfigurer.ownedInputName(source("abc-123", "output", "default")))
                .isEqualTo("dotarec:abc-123");
    }

    @Test
    void buildSettings_outputAndInput_carryDeviceId() {
        JsonObject out = ObsSceneConfigurer.buildSettings(source("1", "output", "{0.0.0}.{guid}"));
        assertThat(out.get("device_id").getAsString()).isEqualTo("{0.0.0}.{guid}");
        assertThat(out.has("window")).isFalse();

        JsonObject in = ObsSceneConfigurer.buildSettings(source("2", "input", "mic-id"));
        assertThat(in.get("device_id").getAsString()).isEqualTo("mic-id");
    }

    @Test
    void buildSettings_nullOrBlankTarget_defaultsDeviceIdToLiteralDefault() {
        // An EMPTY settings object was the root cause of the old failing add; we must write "default".
        assertThat(
                        ObsSceneConfigurer.buildSettings(source("1", "output", null))
                                .get("device_id")
                                .getAsString())
                .isEqualTo("default");
        assertThat(
                        ObsSceneConfigurer.buildSettings(source("2", "input", "  "))
                                .get("device_id")
                                .getAsString())
                .isEqualTo("default");
    }

    @Test
    void buildSettings_application_carriesWindowAndExePriority() {
        JsonObject app = ObsSceneConfigurer.buildSettings(source("1", "application", "::Discord.exe"));
        assertThat(app.get("window").getAsString()).isEqualTo("::Discord.exe");
        // priority 2 = WINDOW_PRIORITY_EXE (match by executable).
        assertThat(app.get("priority").getAsInt()).isEqualTo(2);
        assertThat(app.has("device_id")).isFalse();
    }

    @Test
    void buildSettings_application_nullTarget_writesEmptyWindowButStillCreated() {
        // A null/blank application target matches nothing but the input is still created (not skipped).
        JsonObject app = ObsSceneConfigurer.buildSettings(source("1", "application", null));
        assertThat(app.get("window").getAsString()).isEmpty();
        assertThat(app.get("priority").getAsInt()).isEqualTo(2);
    }

    @Test
    void inputsToRemove_dropsOrphanedOwnedInputsNotInDesired() {
        List<AudioSource> desired =
                List.of(source("keep", "output", "default"), source("also", "input", null));
        Set<String> existing =
                Set.of("dotarec:keep", "dotarec:also", "dotarec:orphan");

        assertThat(ObsSceneConfigurer.inputsToRemove(desired, existing))
                .containsExactly("dotarec:orphan");
    }

    @Test
    void inputsToRemove_emptyDesired_removesAllOwned() {
        Set<String> existing = Set.of("dotarec:a", "dotarec:b");
        assertThat(ObsSceneConfigurer.inputsToRemove(List.of(), existing))
                .containsExactlyInAnyOrder("dotarec:a", "dotarec:b");
    }

    @Test
    void ownedInputNames_keepsOnlyDotarecPrefixed() {
        List<Input> inputs =
                List.of(input("dotarec:a"), input("Game Capture"), input("dotarec:b"), input(null));
        assertThat(ObsSceneConfigurer.ownedInputNames(inputs))
                .containsExactlyInAnyOrder("dotarec:a", "dotarec:b");
    }

    @Test
    void foreignAudioInputs_picksTheBuiltinGlobalsButNotOurOwnInputs() {
        // OBS's built-in Desktop Audio + Mic/Aux globals are WASAPI audio inputs we do NOT own; these
        // are exactly the ones reconcile must mute so they never leak the desktop mix / microphone.
        List<Input> inputs =
                List.of(
                        input("Desktop Audio", "wasapi_output_capture"),
                        input("Mic/Aux", "wasapi_input_capture"),
                        input("dotarec:builtin-desktop", "wasapi_output_capture"),
                        input("dotarec:builtin-microphone", "wasapi_input_capture"),
                        input("dotarec:abc", "wasapi_process_output_capture"));

        assertThat(ObsSceneConfigurer.foreignAudioInputs(inputs))
                .containsExactly("Desktop Audio", "Mic/Aux");
    }

    @Test
    void foreignAudioInputs_excludesEnumerationProbesAndOwnedInputs() {
        // A leftover enumeration probe is handled by removal (probeInputsToRemove), not muting, so the
        // mute set excludes it as well as our own dotarec: inputs — leaving nothing here to mute.
        List<Input> inputs =
                List.of(
                        input(ObsSceneConfigurer.PROBE_PREFIX + "xyz", "wasapi_input_capture"),
                        input("dotarec:abc", "wasapi_process_output_capture"));

        assertThat(ObsSceneConfigurer.foreignAudioInputs(inputs)).isEmpty();
    }

    @Test
    void probeInputsToRemove_picksOnlyProbePrefixedInputs() {
        // Only the transient enumeration probes are swept; the globals (muted instead) and our own
        // managed inputs and Game Capture stay.
        List<Input> inputs =
                List.of(
                        input(ObsSceneConfigurer.PROBE_PREFIX + "1", "wasapi_input_capture"),
                        input(ObsSceneConfigurer.PROBE_PREFIX + "2", "wasapi_output_capture"),
                        input("Desktop Audio", "wasapi_output_capture"),
                        input("dotarec:abc", "wasapi_process_output_capture"),
                        input(ObsSceneConfigurer.GAME_CAPTURE_INPUT, "game_capture"),
                        input(null));

        assertThat(ObsSceneConfigurer.probeInputsToRemove(inputs))
                .containsExactly(
                        ObsSceneConfigurer.PROBE_PREFIX + "1", ObsSceneConfigurer.PROBE_PREFIX + "2");
    }

    @Test
    void foreignAudioInputs_ignoresNonAudioInputsAndNulls() {
        // Game Capture (video) and a null name/kind are not audio leaks; leave them out.
        List<Input> inputs =
                List.of(
                        input(ObsSceneConfigurer.GAME_CAPTURE_INPUT, "game_capture"),
                        input("no-kind"),
                        input(null, "wasapi_output_capture"));

        assertThat(ObsSceneConfigurer.foreignAudioInputs(inputs)).isEmpty();
    }

    @Test
    void reconcileAudioInputs_mutesForeignGlobalsAndSweepsStaleProbes(@TempDir Path dir) {
        // Wiring check for the two reconcile side effects the pure helpers can't prove: a foreign OBS
        // global ("Desktop Audio") is actually MUTED and a leftover enumeration probe is actually
        // REMOVED. Empty audioSources makes the desired-source loop a no-op, isolating these two steps.
        AppPaths paths = new AppPaths(dir.toString(), dir.resolve("obs").toString());
        SettingsStore store = new SettingsStore(paths);
        store.update(
                s -> {
                    s.audioSources = new ArrayList<>();
                    return s;
                });
        ObsSceneConfigurer configurer = new ObsSceneConfigurer(store);

        String staleProbe = ObsSceneConfigurer.PROBE_PREFIX + "stale";
        GetInputListResponse listResponse = mock(GetInputListResponse.class);
        when(listResponse.isSuccessful()).thenReturn(true);
        when(listResponse.getInputs())
                .thenReturn(
                        List.of(
                                input("Desktop Audio", "wasapi_output_capture"),
                                input(staleProbe, "wasapi_input_capture")));

        OBSRemoteController controller = mock(OBSRemoteController.class);
        when(controller.getInputList(any(), anyLong())).thenReturn(listResponse);
        RemoveInputResponse removeOk = mock(RemoveInputResponse.class);
        lenient().when(removeOk.isSuccessful()).thenReturn(true);
        when(controller.removeInput(any(), anyLong())).thenReturn(removeOk);
        SetInputMuteResponse muteOk = mock(SetInputMuteResponse.class);
        lenient().when(muteOk.isSuccessful()).thenReturn(true);
        when(controller.setInputMute(any(), any(), anyLong())).thenReturn(muteOk);

        configurer.reconcileAudioInputs(controller);

        verify(controller).setInputMute(eq("Desktop Audio"), eq(Boolean.TRUE), anyLong());
        verify(controller).removeInput(eq(staleProbe), anyLong());
        // The probe is swept, never muted; the global is muted, never removed.
        verify(controller, never()).setInputMute(eq(staleProbe), any(), anyLong());
        verify(controller, never()).removeInput(eq("Desktop Audio"), anyLong());
    }
}
