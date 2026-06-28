package dev.dotarec.obs;

import com.google.gson.JsonObject;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.config.SettingsStore.AudioSource;
import io.obswebsocket.community.client.OBSRemoteController;
import io.obswebsocket.community.client.message.response.inputs.CreateInputResponse;
import io.obswebsocket.community.client.message.response.inputs.GetInputListResponse;
import io.obswebsocket.community.client.message.response.inputs.RemoveInputResponse;
import io.obswebsocket.community.client.message.response.inputs.SetInputMuteResponse;
import io.obswebsocket.community.client.message.response.inputs.SetInputSettingsResponse;
import io.obswebsocket.community.client.message.response.inputs.SetInputVolumeResponse;
import io.obswebsocket.community.client.message.response.scenes.CreateSceneResponse;
import io.obswebsocket.community.client.message.response.scenes.GetSceneListResponse;
import io.obswebsocket.community.client.message.response.scenes.SetCurrentProgramSceneResponse;
import io.obswebsocket.community.client.model.Input;
import io.obswebsocket.community.client.model.Scene;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
 *   <li>the user's audio source list is reconciled into {@code dotarec:<id>}-named WASAPI inputs;
 *   <li>{@code "Dota"} is the current program scene.
 * </ul>
 *
 * <p>Every step is idempotent: it first reads the existing scene/input list and only creates what
 * is missing, so it is safe for the {@link ObsConnectionScheduler} to call it again after a
 * reconnect, and a user who pre-created a {@code Dota} scene is detected and left alone. Scene and
 * program-scene failures are fatal (throw {@link ObsException}); the audio step degrades to a
 * warning, since a recorder with video but no audio is still useful.
 *
 * <p>The pure decision helpers ({@link #sceneExists}, {@link #inputExists},
 * {@link #kindToObsKind}, {@link #buildSettings}, {@link #ownedInputName}, {@link #inputsToRemove})
 * are package-visible so they can be unit-tested directly, without a live OBS — the
 * network-touching steps wrap them around real obs-websocket calls.
 */
@Component
public class ObsSceneConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ObsSceneConfigurer.class);

    static final String SCENE_NAME = "Dota";
    static final String GAME_CAPTURE_INPUT = "Game Capture";
    static final String GAME_CAPTURE_KIND = "game_capture";

    /** OBS WASAPI input-kind ids (verified from the bundled OBS 32.x win-wasapi.dll). */
    static final String KIND_APPLICATION = "wasapi_process_output_capture";
    static final String KIND_OUTPUT = "wasapi_output_capture";
    static final String KIND_INPUT = "wasapi_input_capture";

    /** App-owned prefix for every input we create, so reconcile can diff/clean only our inputs. */
    static final String OWNED_PREFIX = "dotarec:";

    private static final long REQUEST_TIMEOUT_MS = 5_000L;

    private final SettingsStore settings;

    public ObsSceneConfigurer(SettingsStore settings) {
        this.settings = settings;
    }

    /**
     * Ensures the Dota scene, game-capture input, and the configured audio sources are ready, then
     * activates the scene. Idempotent: safe to call repeatedly. The controller must be live and
     * connected.
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
        reconcileAudioInputs(controller);
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
        // GetInputList's argument is an input-KIND filter, not a scene name; input names are global in
        // OBS v5, so pass null to list ALL inputs and detect an already-existing "Game Capture" by
        // name. (Filtering by the scene name returned an empty list, so a persisted Game Capture went
        // undetected and we'd try to re-create it — which fails because the name is taken.)
        GetInputListResponse inputs = controller.getInputList(null, REQUEST_TIMEOUT_MS);
        if (inputs == null || !inputs.isSuccessful()) {
            throw new ObsException("Failed to fetch input list");
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

    /**
     * Reconciles the user's audio source list into OBS inputs named {@code dotarec:<id>}: creates the
     * missing ones, re-applies settings/volume/mute on the existing ones, and removes any orphaned
     * {@code dotarec:}-prefixed input no longer in the desired list. Replaces the old implicit
     * "Desktop Audio" special-input add. Non-fatal throughout: every per-source failure is logged and
     * skipped (audio degrades, recording still works); only a missing input list returns early.
     *
     * <p>Public so {@code SettingsController} can drive a live apply after a settings PUT without a
     * reconnect; also called from {@link #ensureSceneReady}.
     */
    public void reconcileAudioInputs(OBSRemoteController controller) {
        if (controller == null) {
            return;
        }
        List<AudioSource> desired = settings.get().audioSources;
        if (desired == null) {
            desired = List.of();
        }
        // null kind = ALL inputs (global + scene), so a duplicate input is seen and not re-created.
        GetInputListResponse inputs = controller.getInputList(null, REQUEST_TIMEOUT_MS);
        if (inputs == null || !inputs.isSuccessful()) {
            log.warn("Failed to fetch input list; skipping audio reconcile");
            return;
        }
        Set<String> existingOwned = ownedInputNames(inputs.getInputs());

        for (AudioSource s : desired) {
            String name = ownedInputName(s);
            String obsKind = kindToObsKind(s.kind());
            if (obsKind == null) {
                log.warn("Audio source '{}' has unknown kind '{}'; skipping", name, s.kind());
                continue;
            }
            if (!existingOwned.contains(name)) {
                CreateInputResponse created =
                        controller.createInput(
                                SCENE_NAME,
                                name,
                                obsKind,
                                buildSettings(s),
                                true, // sceneItemEnabled
                                REQUEST_TIMEOUT_MS);
                if (created == null || !created.isSuccessful()) {
                    log.warn("Failed to create audio input '{}'; continuing", name);
                    continue;
                }
                log.info("Created audio input '{}' ({})", name, obsKind);
            } else {
                // overlay = true: merge our keys over the existing settings to re-apply the target.
                SetInputSettingsResponse set =
                        controller.setInputSettings(name, buildSettings(s), true, REQUEST_TIMEOUT_MS);
                if (set == null || !set.isSuccessful()) {
                    log.warn("Failed to update audio input '{}' settings; continuing", name);
                }
            }
            // Always re-apply volume + mute (mul = pct/100.0, linear; 1.0 = 100% = 0 dB).
            double mul = clampVolume(s.volume()) / 100.0;
            SetInputVolumeResponse vol =
                    controller.setInputVolume(name, Double.valueOf(mul), null, REQUEST_TIMEOUT_MS);
            if (vol == null || !vol.isSuccessful()) {
                log.warn("Failed to set volume on audio input '{}'; continuing", name);
            }
            SetInputMuteResponse mute =
                    controller.setInputMute(name, Boolean.valueOf(s.muted()), REQUEST_TIMEOUT_MS);
            if (mute == null || !mute.isSuccessful()) {
                log.warn("Failed to set mute on audio input '{}'; continuing", name);
            }
        }

        // Remove our orphans: dotarec: inputs whose id is no longer in the desired list.
        for (String orphan : inputsToRemove(desired, existingOwned)) {
            RemoveInputResponse removed = controller.removeInput(orphan, REQUEST_TIMEOUT_MS);
            if (removed == null || !removed.isSuccessful()) {
                log.warn("Failed to remove orphaned audio input '{}'; continuing", orphan);
                continue;
            }
            log.info("Removed orphaned audio input '{}'", orphan);
        }
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

    /** The app-owned OBS input name for a source: {@code "dotarec:" + id}. */
    static String ownedInputName(AudioSource s) {
        return OWNED_PREFIX + s.id();
    }

    /**
     * Maps a contract kind ({@code application|output|input}) to its OBS WASAPI input-kind id; returns
     * {@code null} for an unknown kind so the caller can skip it.
     */
    static String kindToObsKind(String kind) {
        if (kind == null) {
            return null;
        }
        switch (kind) {
            case "application":
                return KIND_APPLICATION;
            case "output":
                return KIND_OUTPUT;
            case "input":
                return KIND_INPUT;
            default:
                return null;
        }
    }

    /**
     * Builds the OBS settings JSON for a source's kind. output/input -> {@code {device_id}} (literal
     * {@code "default"} when target is null/blank — an EMPTY settings object was the root cause of the
     * old failing add); application -> {@code {window, priority:2}} (priority 2 = WINDOW_PRIORITY_EXE,
     * match by executable; an empty window for a null/blank target matches nothing but still creates
     * the input).
     */
    static JsonObject buildSettings(AudioSource s) {
        JsonObject json = new JsonObject();
        if ("application".equals(s.kind())) {
            String window = s.target() == null ? "" : s.target();
            json.addProperty("window", window);
            json.addProperty("priority", 2);
        } else {
            String deviceId =
                    (s.target() == null || s.target().isBlank()) ? "default" : s.target();
            json.addProperty("device_id", deviceId);
        }
        return json;
    }

    /** The set of {@code dotarec:}-prefixed input names currently present in OBS (null-safe). */
    static Set<String> ownedInputNames(List<Input> inputs) {
        Set<String> owned = new LinkedHashSet<>();
        if (inputs == null) {
            return owned;
        }
        for (Input i : inputs) {
            String name = i.getInputName();
            if (name != null && name.startsWith(OWNED_PREFIX)) {
                owned.add(name);
            }
        }
        return owned;
    }

    /**
     * Pure diff: the {@code dotarec:}-prefixed input names that are ours-and-orphaned — present in OBS
     * but with no matching id in the desired list — and so must be removed.
     */
    static Set<String> inputsToRemove(List<AudioSource> desired, Set<String> existingOwned) {
        Set<String> desiredNames = new LinkedHashSet<>();
        if (desired != null) {
            for (AudioSource s : desired) {
                desiredNames.add(ownedInputName(s));
            }
        }
        Set<String> remove = new LinkedHashSet<>(existingOwned);
        remove.removeAll(desiredNames);
        return remove;
    }

    /** Clamps a UI volume percent into [0,100]. */
    static int clampVolume(int volume) {
        return Math.max(0, Math.min(100, volume));
    }
}
