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
import io.obswebsocket.community.client.message.response.sceneitems.GetSceneItemIdResponse;
import io.obswebsocket.community.client.message.response.sceneitems.SetSceneItemTransformResponse;
import io.obswebsocket.community.client.model.Input;
import io.obswebsocket.community.client.model.Scene;
import io.obswebsocket.community.client.model.SceneItem;
import dev.dotarec.obs.setup.ObsConfigWriter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 *   <li>input {@code "Game Capture"} of kind {@code game_capture} exists in that scene, locked to
 *       the Dota window ({@code capture_mode=window}, {@code window="Dota 2:SDL_app:dota2.exe"}, exe
 *       priority) so it never hooks some other fullscreen app;
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
 * <p>The pure decision helpers ({@link #sceneExists}, {@link #inputExists}, {@link #buildSettings},
 * {@link #ownedInputName}, {@link #inputsToRemove}) are package-visible so they can be unit-tested
 * directly, without a live OBS — the network-touching steps wrap them around real obs-websocket
 * calls. {@link #kindToObsKind} is public because it is the single contract-kind mapping the bridge's
 * audio paths share.
 */
@Component
public class ObsSceneConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ObsSceneConfigurer.class);

    /** Canonical OBS scene name; the single source of truth for every consumer of the Dota scene. */
    public static final String SCENE_NAME = "Dota";
    static final String GAME_CAPTURE_INPUT = "Game Capture";
    static final String GAME_CAPTURE_KIND = "game_capture";

    /**
     * OBS game-capture mode that captures one specific window (the Dota window), rather than {@code
     * any_fullscreen} which hooks whatever application happens to be fullscreen — the bug where a
     * fullscreen media player/browser (e.g. Jellyfin) got recorded instead of the game.
     */
    static final String CAPTURE_MODE_WINDOW = "window";

    /**
     * Encoded {@code "title:class:exe"} OBS window match for the Dota window: {@code
     * Dota 2:SDL_app:dota2.exe}, paired with {@link #WINDOW_PRIORITY_EXE}. The real title+class are
     * REQUIRED, not cosmetic: OBS's {@code game_capture} window mode parses this string and, on an empty
     * class, {@code ms_find_window} returns null <em>before</em> it ever scores candidates by priority —
     * so an exe-only {@code "::dota2.exe"} selects no window at all, the graphics hook never fires, and
     * every recording comes out pure black (confirmed from OBS logs: zero hook attempts across whole
     * sessions). This is why the video match deliberately differs from the {@code "::dota2.exe"} that
     * {@code SettingsStore} seeds for Dota's process-AUDIO capture: WASAPI process capture resolves an
     * exe-only match through a different code path (no class guard), but game capture cannot. With the
     * real title/class present, {@link #WINDOW_PRIORITY_EXE} still lets capture re-bind by executable
     * should the window title ever drift.
     */
    static final String GAME_CAPTURE_WINDOW_MATCH = "Dota 2:SDL_app:dota2.exe";

    /** OBS window-match priority 2 = {@code WINDOW_PRIORITY_EXE} (match by executable name). */
    static final int WINDOW_PRIORITY_EXE = 2;

    /**
     * OBS bounds type that scales a source to fit inside its bounds box, preserving aspect ratio. We
     * give the game-capture item a bounds box the size of the whole canvas so any game render
     * resolution is scaled to fit (see {@link #fitGameCaptureToCanvas}).
     */
    static final String BOUNDS_SCALE_INNER = "OBS_BOUNDS_SCALE_INNER";
    /** OBS alignment bitmask: top-left (TOP|LEFT). The position anchor sits at the canvas origin. */
    static final int ALIGN_TOP_LEFT = 5;
    /** OBS alignment: centered (0). Centers the fitted source within its canvas-sized bounds box. */
    static final int ALIGN_CENTER = 0;

    /** OBS WASAPI input-kind ids (verified from the bundled OBS 32.x win-wasapi.dll). Public so the
     * audio enumeration endpoint shares one source of truth instead of re-declaring the literals. */
    public static final String KIND_APPLICATION = "wasapi_process_output_capture";
    public static final String KIND_OUTPUT = "wasapi_output_capture";
    public static final String KIND_INPUT = "wasapi_input_capture";

    /**
     * The single mapping from a contract audio kind ({@code application|output|input}) to its OBS
     * WASAPI input-kind id. Every consumer of this contract derives from this one map so they cannot
     * drift apart: {@link #kindToObsKind} looks up here, {@link #CONTRACT_AUDIO_KINDS} exposes its
     * keys for {@code SettingsController}'s allow-list, and {@code AudioController} + {@code
     * ObsController.expectsLiveAudio} route through {@link #kindToObsKind}. Insertion-ordered so the
     * exposed key set reads in the contract's documented order.
     */
    private static final Map<String, String> CONTRACT_KIND_TO_OBS_KIND;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("application", KIND_APPLICATION);
        m.put("output", KIND_OUTPUT);
        m.put("input", KIND_INPUT);
        CONTRACT_KIND_TO_OBS_KIND = java.util.Collections.unmodifiableMap(m);
    }

    /**
     * The contract audio kinds ({@code application|output|input}), backed by {@link
     * #CONTRACT_KIND_TO_OBS_KIND}'s keys so the settings validator's allow-list and the kind-mapping
     * switch are provably the same set. Insertion-ordered so an error message listing it reads in the
     * documented order. Consumed by {@code SettingsController.ALLOWED_AUDIO_KIND}.
     */
    public static final Set<String> CONTRACT_AUDIO_KINDS =
            java.util.Collections.unmodifiableSet(
                    new LinkedHashSet<>(CONTRACT_KIND_TO_OBS_KIND.keySet()));

    /** App-owned prefix for every input we create, so reconcile can diff/clean only our inputs. */
    static final String OWNED_PREFIX = "dotarec:";

    /**
     * Name prefix of the transient hidden inputs {@code AudioController} creates to enumerate a
     * device/window property and then removes. Public so {@code AudioController} mints its probe names
     * from this single source of truth and {@link #probeInputsToRemove} recognises any that a failed
     * removal left behind. A probe should never outlive its one enumerate() call.
     */
    public static final String PROBE_PREFIX = "__dotarec_probe_audio_";

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
        fitGameCaptureToCanvas(controller);
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
            // Already present — but an input created by an older build (or hand-made by the user) may
            // be on capture_mode=any_fullscreen, which hooks whatever app is fullscreen and can record
            // the wrong window. Re-assert the Dota window lock on every connect so a stale input is
            // corrected in place (overlay merges our keys over its existing settings). Non-fatal: a
            // failure warns rather than aborting scene config — mirrors fitGameCaptureToCanvas.
            SetInputSettingsResponse fix =
                    controller.setInputSettings(
                            GAME_CAPTURE_INPUT, gameCaptureSettings(), true, REQUEST_TIMEOUT_MS);
            if (fix == null || !fix.isSuccessful()) {
                log.warn(
                        "Failed to re-lock '{}' to dota2.exe; capture may hook the wrong window",
                        GAME_CAPTURE_INPUT);
            } else {
                log.debug("Ensured '{}' is locked to dota2.exe (capture_mode=window)", GAME_CAPTURE_INPUT);
            }
            // Input names are GLOBAL in OBS v5, so an existing "Game Capture" may live only in some
            // OTHER scene (a user re-parented it, or a hand-edited/restored scene collection). Then the
            // recorded "Dota" program scene has no game-capture item and records pure BLACK — a failure
            // neither the source-level checks nor the black-frame guard reliably catch. Verify it is a
            // scene item of SCENE_NAME. CRUCIAL: distinguish a request TIMEOUT (null response, "unknown")
            // from a genuine "not a scene item of this scene" (an unsuccessful reply). Only the latter
            // drives the DESTRUCTIVE remove+recreate below; a null is SKIPPED (like fitGameCaptureToCanvas
            // treats it), so a transient RPC timeout can never destroy a live, correctly-placed capture
            // mid-recording.
            GetSceneItemIdResponse sceneItem =
                    controller.getSceneItemId(SCENE_NAME, GAME_CAPTURE_INPUT, 0, REQUEST_TIMEOUT_MS);
            SceneMembership membership = classifySceneMembership(sceneItem);
            if (membership == SceneMembership.IN_SCENE) {
                return; // already a scene item of the Dota scene — nothing to repair
            }
            if (membership == SceneMembership.UNKNOWN) {
                // A null response is a request TIMEOUT, not proof of an orphan: leave the (possibly live,
                // correctly-placed) capture untouched so a transient hiccup can't destroy it.
                log.warn(
                        "Could not verify '{}' scene membership (no response); leaving it in place",
                        GAME_CAPTURE_INPUT);
                return;
            }
            // NOT_IN_SCENE: the input exists globally but is NOT a scene item of the Dota scene —
            // remove the orphan and fall through to recreate it in the scene.
            log.warn(
                    "'{}' exists but is not in scene '{}'; re-attaching so the recording is not black",
                    GAME_CAPTURE_INPUT,
                    SCENE_NAME);
            RemoveInputResponse orphan = controller.removeInput(GAME_CAPTURE_INPUT, REQUEST_TIMEOUT_MS);
            if (orphan == null || !orphan.isSuccessful()) {
                log.warn(
                        "Failed to remove orphaned '{}'; scene '{}' may record black",
                        GAME_CAPTURE_INPUT,
                        SCENE_NAME);
                return;
            }
            // fall through to createInput below.
        }
        CreateInputResponse resp =
                controller.createInput(
                        SCENE_NAME,
                        GAME_CAPTURE_INPUT,
                        GAME_CAPTURE_KIND,
                        gameCaptureSettings(),
                        true, // sceneItemEnabled
                        REQUEST_TIMEOUT_MS);
        if (resp == null || !resp.isSuccessful()) {
            throw new ObsException("Failed to create input " + GAME_CAPTURE_INPUT);
        }
        log.info(
                "Created game capture input '{}' locked to dota2.exe (capture_mode=window)",
                GAME_CAPTURE_INPUT);
    }

    /** Result of the game-capture scene-membership probe (see {@link #ensureGameCaptureInput}). */
    enum SceneMembership {
        /** A successful id: the input is a scene item of the Dota scene. */
        IN_SCENE,
        /** An unsuccessful reply (OBS "not a scene item of this scene"): orphaned, needs re-attaching. */
        NOT_IN_SCENE,
        /** A null response (request timeout): unknown — must NOT drive the destructive remove/recreate. */
        UNKNOWN
    }

    /**
     * Classifies a {@code getSceneItemId} response for {@link #GAME_CAPTURE_INPUT}. Pure and
     * package-visible so the critical {@code null} (timeout → {@link SceneMembership#UNKNOWN}) vs
     * unsuccessful ({@link SceneMembership#NOT_IN_SCENE}) distinction is unit-tested without a live OBS:
     * only NOT_IN_SCENE may drive the destructive remove/recreate, so a transient timeout can never
     * destroy a live, correctly-placed capture.
     */
    static SceneMembership classifySceneMembership(GetSceneItemIdResponse id) {
        if (id == null) {
            return SceneMembership.UNKNOWN;
        }
        return (id.isSuccessful() && id.getSceneItemId() != null)
                ? SceneMembership.IN_SCENE
                : SceneMembership.NOT_IN_SCENE;
    }

    /**
     * The OBS {@code game_capture} settings that pin capture to the Dota window: {@code
     * capture_mode=window} matching {@link #GAME_CAPTURE_WINDOW_MATCH} by executable ({@link
     * #WINDOW_PRIORITY_EXE}). Used for both the initial create and the every-connect re-lock, so the
     * two paths can't disagree. Package-visible for a direct unit test of the produced JSON.
     */
    static JsonObject gameCaptureSettings() {
        JsonObject settings = new JsonObject();
        settings.addProperty("capture_mode", CAPTURE_MODE_WINDOW);
        settings.addProperty("window", GAME_CAPTURE_WINDOW_MATCH);
        settings.addProperty("priority", WINDOW_PRIORITY_EXE);
        return settings;
    }

    /**
     * Scales the game-capture scene item to fit the OBS canvas. Game Capture brings the source in at
     * the game's native render resolution and OBS places a new scene item unscaled at the top-left, so
     * a game rendering larger than the canvas (e.g. Dota at 4K into a 1920x1080 canvas) is cropped to
     * the top-left region -- the "only a quarter of the screen is recorded" bug. A {@code
     * SCALE_INNER} bounds box spanning the whole canvas makes OBS scale the source to fit (down or up),
     * preserving aspect ratio, for any game/display resolution without us having to know it up front.
     *
     * <p>Idempotent and non-fatal: re-applied on every connect (so a pre-existing, untransformed
     * "Game Capture" is corrected too), and a failure warns rather than throwing -- a cropped
     * recording still beats aborting scene config and recording nothing.
     */
    private void fitGameCaptureToCanvas(OBSRemoteController controller) {
        int[] canvas = ObsConfigWriter.parseResolution(settings.get().resolution);
        GetSceneItemIdResponse id =
                controller.getSceneItemId(SCENE_NAME, GAME_CAPTURE_INPUT, 0, REQUEST_TIMEOUT_MS);
        if (id == null || !id.isSuccessful() || id.getSceneItemId() == null) {
            log.warn(
                    "Could not resolve scene-item id for '{}'; skipping fit-to-canvas (capture may be"
                            + " cropped)",
                    GAME_CAPTURE_INPUT);
            return;
        }
        // Bounds box = whole canvas, scale-inner = fit preserving aspect; position the box at the
        // origin (top-left anchor) and center the fitted source inside it. Leave scale/size null so
        // OBS derives them from the bounds.
        SceneItem.Transform transform =
                SceneItem.Transform.builder()
                        .positionX(0f)
                        .positionY(0f)
                        .alignment(ALIGN_TOP_LEFT)
                        .boundsType(BOUNDS_SCALE_INNER)
                        .boundsAlignment(ALIGN_CENTER)
                        .boundsWidth((float) canvas[0])
                        .boundsHeight((float) canvas[1])
                        .build();
        SetSceneItemTransformResponse resp =
                controller.setSceneItemTransform(
                        SCENE_NAME, id.getSceneItemId(), transform, REQUEST_TIMEOUT_MS);
        if (resp == null || !resp.isSuccessful()) {
            log.warn(
                    "Failed to fit '{}' to the {}x{} canvas; capture may be cropped",
                    GAME_CAPTURE_INPUT,
                    canvas[0],
                    canvas[1]);
            return;
        }
        log.info(
                "Fit game capture to {}x{} canvas (bounds scale-inner)", canvas[0], canvas[1]);
    }

    /**
     * Reconciles the user's audio source list into OBS inputs named {@code dotarec:<id>}: creates the
     * missing ones, re-applies settings/volume/mute on the existing ones, and removes any orphaned
     * {@code dotarec:}-prefixed input no longer in the desired list. Replaces the old implicit
     * "Desktop Audio" special-input add. Non-fatal throughout: every per-source failure is logged and
     * skipped (audio degrades, recording still works); only a missing input list returns early.
     *
     * <p>Public so {@code SettingsController} can drive a live apply after a settings PUT without a
     * reconnect; also called from {@link #ensureSceneReady}. {@code synchronized} so a settings-PUT
     * reconcile and the connect-edge reconfigure (different threads) can't interleave their
     * create/setSettings/remove sequences against the same OBS inputs.
     */
    public synchronized void reconcileAudioInputs(OBSRemoteController controller) {
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
        // name -> current OBS input kind, so a source whose KIND changed (same id) is recreated rather
        // than have settings overlaid onto an input of the wrong kind (an OBS input's kind is immutable).
        Map<String, String> existingOwned = ownedInputKinds(inputs.getInputs());

        for (AudioSource s : desired) {
            String name = ownedInputName(s);
            String obsKind = kindToObsKind(s.kind());
            if (obsKind == null) {
                log.warn("Audio source '{}' has unknown kind '{}'; skipping", name, s.kind());
                continue;
            }
            if (!isEffectiveSource(s)) {
                // Unconfigured (an application capture with no window picked yet): creating it would
                // make an input that captures nothing but still counts toward readiness (isReady -> a
                // silent recording). Skip it and remove any stale input from when it was configured.
                if (existingOwned.containsKey(name)) {
                    RemoveInputResponse rm = controller.removeInput(name, REQUEST_TIMEOUT_MS);
                    if (rm != null && rm.isSuccessful()) {
                        log.info("Removed audio input '{}' (source has no target)", name);
                    }
                }
                continue;
            }
            boolean exists = existingOwned.containsKey(name);
            if (exists && !obsKind.equals(existingOwned.get(name))) {
                // Kind changed (e.g. application -> output). Remove the stale-kind input and fall
                // through to recreate it with the new kind below.
                RemoveInputResponse changed = controller.removeInput(name, REQUEST_TIMEOUT_MS);
                if (changed == null || !changed.isSuccessful()) {
                    log.warn("Failed to remove audio input '{}' to change its kind; continuing", name);
                } else {
                    log.info("Recreating audio input '{}' for kind change -> {}", name, obsKind);
                }
                exists = false;
            }
            if (!exists) {
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
        for (String orphan : inputsToRemove(desired, existingOwned.keySet())) {
            RemoveInputResponse removed = controller.removeInput(orphan, REQUEST_TIMEOUT_MS);
            if (removed == null || !removed.isSuccessful()) {
                log.warn("Failed to remove orphaned audio input '{}'; continuing", orphan);
                continue;
            }
            log.info("Removed orphaned audio input '{}'", orphan);
        }

        // Sweep leftover audio-enumeration probes. AudioController removes each probe in a finally, so
        // any still present is an orphan from a failed/raced removal; without this they pile up as
        // permanent hidden scene items. Race-safe even though AudioController.enumerate is not serialized
        // against this method (e.g. settings-PUT reconciles run while a settings picker primes audio):
        // we only ever remove probes present in the input snapshot taken at the top of this reconcile, so
        // a probe created by a concurrent live enumeration AFTER that snapshot is invisible here and never
        // touched, and a removeInput on an already-removed probe is a harmless debug-logged no-op.
        for (String probe : probeInputsToRemove(inputs.getInputs())) {
            RemoveInputResponse rmProbe = controller.removeInput(probe, REQUEST_TIMEOUT_MS);
            if (rmProbe == null || !rmProbe.isSuccessful()) {
                log.debug("Failed to remove orphaned enumeration probe '{}'; continuing", probe);
                continue;
            }
            log.debug("Removed orphaned enumeration probe '{}'", probe);
        }

        // Silence the audio inputs we do NOT own — OBS's built-in "Desktop Audio" and "Mic/Aux" globals.
        // They are unmuted out of the box, so without this they leak the user's whole desktop mix (incl.
        // Discord) and microphone into every recording even when the mixer only lists the game. Muting
        // (not removing) is reversible and survives an OBS restart; the user's own mic/desktop capture,
        // when wanted, comes from a managed dotarec: input instead.
        for (String foreign : foreignAudioInputs(inputs.getInputs())) {
            SetInputMuteResponse muted = controller.setInputMute(foreign, Boolean.TRUE, REQUEST_TIMEOUT_MS);
            if (muted == null || !muted.isSuccessful()) {
                log.warn("Failed to mute non-managed audio input '{}'; continuing", foreign);
                continue;
            }
            log.debug("Muted non-managed audio input '{}' (kept out of the recording)", foreign);
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
     * True when a source would actually capture something. An {@code application} source needs a chosen
     * window (a blank target matches no process); {@code output}/{@code input} coerce a blank target to
     * the system default, so they are always effective.
     */
    static boolean isEffectiveSource(AudioSource s) {
        if ("application".equals(s.kind())) {
            return s.target() != null && !s.target().isBlank();
        }
        return true;
    }

    /**
     * Maps a contract kind ({@code application|output|input}) to its OBS WASAPI input-kind id; returns
     * {@code null} for an unknown kind so the caller can skip it. Public and the single source: the
     * bridge's {@code AudioController} (enumeration) and {@code ObsController.expectsLiveAudio}
     * (readiness gate) both route through it so the mapping can't drift across the three paths.
     */
    public static String kindToObsKind(String kind) {
        if (kind == null) {
            return null;
        }
        return CONTRACT_KIND_TO_OBS_KIND.get(kind);
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
     * Pure: the names of WASAPI <em>audio</em> inputs we do NOT own — OBS's built-in Desktop Audio /
     * Mic-Aux globals — which {@link #reconcileAudioInputs} mutes so they never leak into a recording.
     * An input qualifies when its kind is one of the three WASAPI capture kinds AND its name is neither
     * {@code dotarec:}-prefixed (our managed sources, controlled per-source) nor a {@link #PROBE_PREFIX}
     * enumeration probe (those are removed, not muted, by {@link #probeInputsToRemove}). Non-audio
     * inputs (e.g. {@code Game Capture}) are ignored. Null-safe; null name/kind entries are skipped.
     */
    static Set<String> foreignAudioInputs(List<Input> inputs) {
        Set<String> foreign = new LinkedHashSet<>();
        if (inputs == null) {
            return foreign;
        }
        for (Input i : inputs) {
            String name = i.getInputName();
            String kind = i.getInputKind();
            if (name == null || kind == null || name.startsWith(OWNED_PREFIX)
                    || name.startsWith(PROBE_PREFIX)) {
                continue;
            }
            if (KIND_APPLICATION.equals(kind) || KIND_OUTPUT.equals(kind) || KIND_INPUT.equals(kind)) {
                foreign.add(name);
            }
        }
        return foreign;
    }

    /**
     * Pure: the names of leftover audio-enumeration probe inputs ({@link #PROBE_PREFIX}-prefixed).
     * {@code AudioController} creates one of these hidden inputs to enumerate a property and removes it
     * in a {@code finally}; a probe outlives that single call only when its removal failed or raced, so
     * any probe still present is an orphan. {@link #reconcileAudioInputs} removes them so they don't
     * accumulate as permanent hidden scene items. Null-safe; null names are skipped.
     */
    static Set<String> probeInputsToRemove(List<Input> inputs) {
        Set<String> probes = new LinkedHashSet<>();
        if (inputs == null) {
            return probes;
        }
        for (Input i : inputs) {
            String name = i.getInputName();
            if (name != null && name.startsWith(PROBE_PREFIX)) {
                probes.add(name);
            }
        }
        return probes;
    }

    /** Map of {@code dotarec:}-prefixed input name -> its current OBS input kind (null-safe). */
    static Map<String, String> ownedInputKinds(List<Input> inputs) {
        Map<String, String> owned = new LinkedHashMap<>();
        if (inputs == null) {
            return owned;
        }
        for (Input i : inputs) {
            String name = i.getInputName();
            if (name != null && name.startsWith(OWNED_PREFIX)) {
                owned.put(name, i.getInputKind());
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
