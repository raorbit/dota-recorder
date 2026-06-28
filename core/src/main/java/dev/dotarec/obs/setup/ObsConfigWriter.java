package dev.dotarec.obs.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Auto-configures the bundled portable OBS so the user never touches it.
 *
 * <p>Idempotent; run once at startup by {@link ObsConfigBootstrap}. It:
 *
 * <ol>
 *   <li><b>Credentials</b> — generates a {@link SecureRandom} obs-websocket password (persisted to
 *       {@code settings.json}) and pins the managed port 4466, once. The supervisor (PR3) passes
 *       these to OBS and {@link dev.dotarec.obs.ObsController} connects with them.</li>
 *   <li><b>First-run copy</b> — materializes the read-only bundled OBS into the writable
 *       {@link AppPaths#obsDir()} and drops the portable-mode marker, re-copying only on a version
 *       bump. Skipped when no source dir is configured (dev/tests).</li>
 *   <li><b>WebSocket enable</b> — writes the obs-websocket module {@code config.json} with the
 *       server enabled. This is the durable enable (survives restarts), unlike {@code global.ini}
 *       which OBS #11665 wipes each boot.</li>
 *   <li><b>Recording profile</b> — writes a Simple-output {@code basic.ini} (hybrid MP4, the probed
 *       encoder, the video output dir, the configured resolution).</li>
 * </ol>
 *
 * <p>The Dota scene + {@code game_capture} input are intentionally NOT written here: they are
 * created at runtime over obs-websocket (PR3), against a live OBS that validates them, rather than
 * hand-authored as a fragile static scene-collection JSON.
 */
@Component
public class ObsConfigWriter {

    private static final Logger log = LoggerFactory.getLogger(ObsConfigWriter.class);

    private static final int MANAGED_PORT = 4466;
    private static final String PROFILE_TEMPLATE = "obs/profile-basic.ini";

    private final AppPaths paths;
    private final SettingsStore settings;
    private final EncoderProbe encoderProbe;
    private final String sourceDir;
    private final String obsVersion;
    private final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public ObsConfigWriter(
            AppPaths paths,
            SettingsStore settings,
            EncoderProbe encoderProbe,
            @Value("${app.obs.source-dir:}") String sourceDir,
            @Value("${app.obs.version:0}") String obsVersion) {
        this.paths = paths;
        this.settings = settings;
        this.encoderProbe = encoderProbe;
        this.sourceDir = sourceDir;
        this.obsVersion = obsVersion;
    }

    /** Runs the full idempotent configuration. */
    public synchronized void configure() {
        ObsLayout layout = new ObsLayout(paths.obsDir());
        ensureSettings();
        firstRunCopy(layout);
        writeWebsocketConfig(layout);
        writeProfile(layout);
        log.info("OBS auto-config ready at {}", layout.root());
    }

    /**
     * Re-writes only {@code basic.ini} from the current settings, with NONE of the {@link #configure}
     * side effects (no first-run copy, no websocket-config rewrite, no GPU probe). Called best-effort
     * after a {@code PUT /settings} so the recording profile is fresh for the NEXT OBS launch rather
     * than stale until the next reboot. Like {@link #configure}, this still only affects recordings
     * started on the next OBS launch — a live OBS keeps its already-loaded profile.
     */
    public synchronized void applyProfile() {
        writeProfile(new ObsLayout(paths.obsDir()));
    }

    /**
     * Pins the obs-websocket password, the managed port, and the auto-detected encoder in a single
     * atomic settings update so the read-modify-write cannot race a concurrent {@code PUT /settings}.
     * The (possibly slow) GPU probe runs OUTSIDE the settings lock; its result is applied only if the
     * encoder is still unset when the update commits.
     */
    private void ensureSettings() {
        String current = settings.get().encoder;
        String probed = (current == null || current.isBlank()) ? encoderProbe.detect() : null;
        settings.update(
                s -> {
                    if (s.obsPassword == null || s.obsPassword.isBlank()) {
                        s.obsPassword = generatePassword();
                    }
                    if (s.obsPort <= 0) {
                        s.obsPort = MANAGED_PORT;
                    }
                    if (probed != null && (s.encoder == null || s.encoder.isBlank())) {
                        s.encoder = probed;
                    }
                    return s;
                });
    }

    /** 24-char URL-safe token (144 bits of entropy) — ample for a loopback-only server. */
    private static String generatePassword() {
        byte[] buf = new byte[18];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private void firstRunCopy(ObsLayout layout) {
        if (sourceDir == null || sourceDir.isBlank()) {
            log.debug("No app.obs.source-dir set; skipping OBS first-run copy.");
            return;
        }
        Path source = Path.of(sourceDir);
        Path sourceObs64 = source.resolve("bin").resolve("64bit").resolve("obs64.exe");
        if (!Files.isRegularFile(sourceObs64)) {
            log.warn("OBS source {} has no bin/64bit/obs64.exe; skipping copy.", source);
            return;
        }
        if (alreadyMaterialized(layout)) {
            log.debug("OBS {} already materialized at {}; skipping copy.", obsVersion, layout.root());
            return;
        }
        try {
            if (Files.isRegularFile(layout.obs64())) {
                // Version bump over an existing install: copyTree only overwrites, never deletes, so a
                // file removed in the new OBS would linger and could be loaded as a stale plugin. Prune
                // the bundle-owned trees first; config/obs-studio and our markers are left untouched.
                log.info("Re-materializing OBS {} -> {} (pruning stale files)", obsVersion, layout.root());
                pruneBundleTrees(layout.root());
            } else {
                log.info("Materializing bundled OBS {} -> {}", obsVersion, layout.root());
            }
            copyTree(source, layout.root());
            Files.writeString(layout.portableMarker(), "");
            Files.writeString(layout.versionStamp(), obsVersion);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to copy bundled OBS into " + layout.root(), e);
        }
    }

    /** The top-level trees an OBS portable zip ships; pruned wholesale before a version-bump re-copy. */
    private static final String[] BUNDLE_TREES = {"bin", "data", "obs-plugins"};

    /** Removes the bundle-owned subtrees under {@code root}, preserving config/obs-studio and markers. */
    private static void pruneBundleTrees(Path root) throws IOException {
        for (String tree : BUNDLE_TREES) {
            deleteRecursively(root.resolve(tree));
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            // Deepest entries first so each directory is empty before it is removed.
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }

    private boolean alreadyMaterialized(ObsLayout layout) {
        if (!Files.isRegularFile(layout.obs64())) {
            return false;
        }
        try {
            return Files.isReadable(layout.versionStamp())
                    && obsVersion.equals(Files.readString(layout.versionStamp()).trim());
        } catch (IOException e) {
            return false;
        }
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = dst.resolve(src.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void writeWebsocketConfig(ObsLayout layout) {
        SettingsStore.Settings s = settings.get();
        // obs-websocket 5.x ConfigData keys (snake_case). server_enabled here is the durable enable.
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("alerts_enabled", false);
        cfg.put("auth_required", true);
        cfg.put("first_load", false);
        cfg.put("server_enabled", true);
        cfg.put("server_password", s.obsPassword);
        cfg.put("server_port", s.obsPort);
        Path file = layout.websocketConfig();
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), cfg);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write obs-websocket config: " + file, e);
        }
    }

    private void writeProfile(ObsLayout layout) {
        SettingsStore.Settings s = settings.get();
        int[] res = parseResolution(s.resolution);
        // ensureSettings() has already resolved+persisted the encoder (auto-detect on blank); this is
        // a defensive fallback only, and never mutates/saves settings here.
        String encoder = (s.encoder == null || s.encoder.isBlank()) ? EncoderProbe.X264 : s.encoder;
        String recPath =
                (s.videoDir == null || s.videoDir.isBlank())
                        ? paths.videoDir().toString()
                        : s.videoDir;
        // OBS stores SimpleOutput.FilePath in Qt QSettings INI form, where a single backslash is an
        // escape character -- a Windows path written with single backslashes (C:\Users\...) is
        // mangled on read (\U, \r, ... become bogus escapes), so OBS rejects it as a "bad output
        // path" and recording never starts (no OUTPUT_STARTED -> every match aborts). OBS itself
        // writes this key with forward slashes; match that so the path round-trips intact.
        String recPathIni = recPath.replace('\\', '/');
        String ini =
                loadTemplate(PROFILE_TEMPLATE)
                        .replace("@REC_PATH@", recPathIni)
                        .replace("@REC_ENCODER@", encoder)
                        .replace("@BASE_CX@", Integer.toString(res[0]))
                        .replace("@BASE_CY@", Integer.toString(res[1]))
                        .replace("@OUT_CX@", Integer.toString(res[0]))
                        .replace("@OUT_CY@", Integer.toString(res[1]))
                        // FPS/quality/format are user settings (defaults backfilled in SettingsStore.load).
                        // Guard null/blank defensively in case a legacy settings.json bypassed the backfill.
                        .replace("@FPS@", Integer.toString(s.fps > 0 ? s.fps : 60))
                        .replace(
                                "@REC_QUALITY@",
                                (s.quality == null || s.quality.isBlank()) ? "HQ" : s.quality)
                        .replace(
                                "@REC_FORMAT@",
                                (s.format == null || s.format.isBlank()) ? "hybrid_mp4" : s.format);
        Path file = layout.profileIni();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, ini, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write OBS profile: " + file, e);
        }
    }

    /** Parses {@code "1920x1080"} -> {@code {1920, 1080}}; falls back to 1080p on anything odd. */
    static int[] parseResolution(String resolution) {
        if (resolution != null) {
            String[] parts = resolution.toLowerCase().split("x", 2);
            if (parts.length == 2) {
                try {
                    int w = Integer.parseInt(parts[0].trim());
                    int h = Integer.parseInt(parts[1].trim());
                    if (w > 0 && h > 0) {
                        return new int[] {w, h};
                    }
                } catch (NumberFormatException ignored) {
                    // fall through to default
                }
            }
        }
        return new int[] {1920, 1080};
    }

    private String loadTemplate(String resource) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read template " + resource, e);
        }
    }
}
