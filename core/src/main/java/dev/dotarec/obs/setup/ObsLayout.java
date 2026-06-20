package dev.dotarec.obs.setup;

import java.nio.file.Path;

/**
 * Resolves the on-disk layout of a portable OBS install rooted at a writable directory.
 *
 * <p>OBS in portable mode keeps everything under the install root: the binaries it was extracted
 * with ({@code bin/}, {@code data/}, {@code obs-plugins/}) plus a {@code config/obs-studio/} tree
 * it reads at launch. The auto-config writer materializes the binaries (first-run copy) and writes
 * the config tree; PR3 launches {@code obs64.exe} from the same root. Centralizing the paths here
 * keeps the writer, the (later) supervisor, and the tests on one definition.
 */
public final class ObsLayout {

    /** Profile + scene-collection names the launch flags (PR3) select by. */
    public static final String PROFILE_NAME = "DotaRecorder";
    public static final String SCENE_COLLECTION = "DotaRecorder";

    private final Path root;

    public ObsLayout(Path root) {
        this.root = root;
    }

    /** Writable OBS install root. */
    public Path root() {
        return root;
    }

    /** The OBS executable, present only once the first-run copy has run. */
    public Path obs64() {
        return root.resolve("bin").resolve("64bit").resolve("obs64.exe");
    }

    /** Portable-mode marker: its presence makes OBS read/write config under {@link #root()}. */
    public Path portableMarker() {
        return root.resolve("portable_mode.txt");
    }

    /** Our copy stamp (the bundled OBS version) — drives re-copy on a version bump. */
    public Path versionStamp() {
        return root.resolve(".dotarec-obs-version");
    }

    private Path obsStudioConfig() {
        return root.resolve("config").resolve("obs-studio");
    }

    /**
     * obs-websocket module config. This is the DURABLE place to enable the server: unlike
     * {@code global.ini}'s {@code [OBSWebSocket]} section (wiped each boot by OBS #11665), this
     * file survives restarts.
     */
    public Path websocketConfig() {
        return obsStudioConfig()
                .resolve("plugin_config")
                .resolve("obs-websocket")
                .resolve("config.json");
    }

    /** Directory of our generated recording profile. */
    public Path profileDir() {
        return obsStudioConfig().resolve("basic").resolve("profiles").resolve(PROFILE_NAME);
    }

    /** {@code basic.ini} of our generated recording profile. */
    public Path profileIni() {
        return profileDir().resolve("basic.ini");
    }

    /** Our scene collection file (written at runtime over obs-websocket in PR3, not here). */
    public Path sceneCollection() {
        return obsStudioConfig()
                .resolve("basic")
                .resolve("scenes")
                .resolve(SCENE_COLLECTION + ".json");
    }
}
