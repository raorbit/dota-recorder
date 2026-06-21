package dev.dotarec.obs;

import com.google.gson.JsonObject;
import io.obswebsocket.community.client.OBSRemoteController;
import io.obswebsocket.community.client.message.response.inputs.CreateInputResponse;
import io.obswebsocket.community.client.message.response.inputs.GetInputListResponse;
import io.obswebsocket.community.client.message.response.inputs.GetSpecialInputsResponse;
import io.obswebsocket.community.client.message.response.scenes.CreateSceneResponse;
import io.obswebsocket.community.client.message.response.scenes.GetSceneListResponse;
import io.obswebsocket.community.client.message.response.scenes.SetCurrentProgramSceneResponse;
import io.obswebsocket.community.client.model.Input;
import io.obswebsocket.community.client.model.Scene;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Idempotent runtime scene setup for the Dota recorder, driven over obs-websocket v5.
 *
 * <p>Once OBS is connected, {@link #ensureSceneReady(OBSRemoteController)} ensures:
 *
 * <ul>
 *   <li>scene {@code "Dota"} exists;
 *   <li>input {@code "Game Capture"} of kind {@code game_capture} (with {@code
 *       capture_mode=any_fullscreen}) exists in that scene;
 *   <li>the desktop-audio special input ({@code wasapi_output_capture}) is added to the scene;
 *   <li>{@code "Dota"} is the current program scene.
 * </ul>
 *
 * <p>Every step is idempotent: it first reads the existing scene/input list and only creates what
 * is missing, so it is safe for the {@link ObsConnectionScheduler} to call it again after a
 * reconnect, and a user who pre-created a {@code Dota} scene is detected and left alone. Scene and
 * program-scene failures are fatal (throw {@link ObsException}); the desktop-audio step degrades to
 * a warning, since a recorder with video but no system audio is still useful.
 *
 * <p>The pure decision helpers ({@link #sceneExists}, {@link #inputExists},
 * {@link #pickDesktopAudio}) are package-visible so they can be unit-tested directly, without a
 * live OBS — the network-touching steps wrap them around real obs-websocket calls.
 */
@Component
public class ObsSceneConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ObsSceneConfigurer.class);

    static final String SCENE_NAME = "Dota";
    static final String GAME_CAPTURE_INPUT = "Game Capture";
    static final String GAME_CAPTURE_KIND = "game_capture";
    static final String DESKTOP_AUDIO_KIND = "wasapi_output_capture";
    private static final long REQUEST_TIMEOUT_MS = 5_000L;

    /**
     * Ensures the Dota scene, game-capture input, and desktop audio are ready, then activates the
     * scene. Idempotent: safe to call repeatedly. The controller must be live and connected.
     *
     * @param controller a connected {@link OBSRemoteController}
     * @throws Exception on a fatal, non-recoverable OBS error
     */
    public void ensureSceneReady(OBSRemoteController controller) throws Exception {
        if (controller == null) {
            throw new IllegalArgumentException("Controller cannot be null");
        }
        ensureScene(controller);
        ensureGameCaptureInput(controller);
        ensureDesktopAudio(controller);
        ensureProgramScene(controller);
        log.info("OBS scene '{}' is fully configured and active", SCENE_NAME);
    }

    private void ensureScene(OBSRemoteController controller) {
        GetSceneListResponse sceneList = controller.getSceneList(REQUEST_TIMEOUT_MS);
        if (sceneList == null || !sceneList.isSuccessful()) {
            throw new ObsException("Failed to fetch scene list");
        }
        if (sceneExists(sceneList.getScenes(), SCENE_NAME)) {
            log.debug("Scene '{}' already exists", SCENE_NAME);
            return;
        }
        CreateSceneResponse resp = controller.createScene(SCENE_NAME, REQUEST_TIMEOUT_MS);
        if (resp == null || !resp.isSuccessful()) {
            throw new ObsException("Failed to create scene " + SCENE_NAME);
        }
        log.info("Created scene '{}'", SCENE_NAME);
    }

    private void ensureGameCaptureInput(OBSRemoteController controller) {
        GetInputListResponse inputs = controller.getInputList(SCENE_NAME, REQUEST_TIMEOUT_MS);
        if (inputs == null || !inputs.isSuccessful()) {
            throw new ObsException("Failed to fetch input list for scene " + SCENE_NAME);
        }
        if (inputExists(inputs.getInputs(), GAME_CAPTURE_INPUT)) {
            log.debug("Game capture input '{}' already exists", GAME_CAPTURE_INPUT);
            return;
        }
        JsonObject settings = new JsonObject();
        settings.addProperty("capture_mode", "any_fullscreen");
        CreateInputResponse resp =
                controller.createInput(
                        SCENE_NAME,
                        GAME_CAPTURE_INPUT,
                        GAME_CAPTURE_KIND,
                        settings,
                        true, // sceneItemEnabled
                        REQUEST_TIMEOUT_MS);
        if (resp == null || !resp.isSuccessful()) {
            throw new ObsException("Failed to create input " + GAME_CAPTURE_INPUT);
        }
        log.info(
                "Created game capture input '{}' with capture_mode=any_fullscreen",
                GAME_CAPTURE_INPUT);
    }

    private void ensureDesktopAudio(OBSRemoteController controller) {
        GetSpecialInputsResponse special = controller.getSpecialInputs(REQUEST_TIMEOUT_MS);
        if (special == null || !special.isSuccessful()) {
            log.warn("Failed to fetch special inputs; desktop audio may not be available");
            return;
        }
        String desktopAudio = pickDesktopAudio(special.getDesktop1(), special.getDesktop2());
        if (desktopAudio == null) {
            log.warn("No desktop audio input found; continuing without audio setup");
            return;
        }
        GetInputListResponse inputs = controller.getInputList(SCENE_NAME, REQUEST_TIMEOUT_MS);
        if (inputs == null || !inputs.isSuccessful()) {
            log.warn("Could not check if desktop audio is already added");
            return;
        }
        if (inputExists(inputs.getInputs(), desktopAudio)) {
            log.debug("Desktop audio input '{}' already exists", desktopAudio);
            return;
        }
        CreateInputResponse resp =
                controller.createInput(
                        SCENE_NAME,
                        desktopAudio,
                        DESKTOP_AUDIO_KIND,
                        new JsonObject(),
                        true, // sceneItemEnabled
                        REQUEST_TIMEOUT_MS);
        if (resp == null || !resp.isSuccessful()) {
            log.warn("Failed to add desktop audio input '{}'; continuing", desktopAudio);
            return;
        }
        log.info("Added desktop audio input '{}'", desktopAudio);
    }

    private void ensureProgramScene(OBSRemoteController controller) {
        SetCurrentProgramSceneResponse resp =
                controller.setCurrentProgramScene(SCENE_NAME, REQUEST_TIMEOUT_MS);
        if (resp == null || !resp.isSuccessful()) {
            throw new ObsException("Failed to set program scene to " + SCENE_NAME);
        }
        log.info("Set program scene to '{}'", SCENE_NAME);
    }

    /** True when a scene with {@code name} is present in the list (null-safe). */
    static boolean sceneExists(List<Scene> scenes, String name) {
        return scenes != null
                && scenes.stream().anyMatch(scene -> name.equals(scene.getSceneName()));
    }

    /** True when an input with {@code name} is present in the list (null-safe). */
    static boolean inputExists(List<Input> inputs, String name) {
        return inputs != null
                && inputs.stream().anyMatch(input -> name.equals(input.getInputName()));
    }

    /**
     * Chooses the desktop-audio special input to wire up, preferring {@code desktop1} and falling
     * back to {@code desktop2}; returns {@code null} when neither is set (blank counts as unset).
     */
    static String pickDesktopAudio(String desktop1, String desktop2) {
        if (desktop1 != null && !desktop1.isBlank()) {
            return desktop1;
        }
        if (desktop2 != null && !desktop2.isBlank()) {
            return desktop2;
        }
        return null;
    }
}
