package dev.dotarec.obs;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import dev.dotarec.config.SettingsStore.AudioSource;
import io.obswebsocket.community.client.model.Input;
import io.obswebsocket.community.client.model.Scene;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
}
