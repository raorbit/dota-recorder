package dev.dotarec.obs;

import static org.assertj.core.api.Assertions.assertThat;

import io.obswebsocket.community.client.model.Input;
import io.obswebsocket.community.client.model.Scene;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the deterministic decision logic of {@link ObsSceneConfigurer} -- the
 * already-exists checks and desktop-audio selection that decide whether each setup step creates
 * something or no-ops. These are the parts that make the configurer idempotent; the obs-websocket
 * calls around them need a live OBS and are exercised manually (PR5), not here.
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
    void pickDesktopAudio_prefersDesktop1() {
        assertThat(ObsSceneConfigurer.pickDesktopAudio("Desktop Audio", "Desktop Audio 2"))
                .isEqualTo("Desktop Audio");
    }

    @Test
    void pickDesktopAudio_fallsBackToDesktop2_whenDesktop1Blank() {
        assertThat(ObsSceneConfigurer.pickDesktopAudio(null, "Desktop Audio 2"))
                .isEqualTo("Desktop Audio 2");
        assertThat(ObsSceneConfigurer.pickDesktopAudio("   ", "Desktop Audio 2"))
                .isEqualTo("Desktop Audio 2");
    }

    @Test
    void pickDesktopAudio_nullWhenNeitherSet() {
        assertThat(ObsSceneConfigurer.pickDesktopAudio(null, null)).isNull();
        assertThat(ObsSceneConfigurer.pickDesktopAudio("", "  ")).isNull();
    }
}
