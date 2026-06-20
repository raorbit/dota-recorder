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
        ensureCredentials();
        firstRunCopy(layout);
        writeWebsocketConfig(layout);
        writeProfile(layout);
        log.info("OBS auto-config ready at {}", layout.root());
    }

    private void ensureCredentials() {
        SettingsStore.Settings s = settings.get();
        boolean changed = false;
        if (s.obsPassword == null || s.obsPassword.isBlank()) {
            s.obsPassword = generatePassword();
            changed = true;
        }
        if (s.obsPort <= 0) {
            s.obsPort = MANAGED_PORT;
            changed = true;
        }
        if (changed) {
            settings.save(s);
        }
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
            log.info("Materializing bundled OBS {} -> {}", obsVersion, layout.root());
            copyTree(source, layout.root());
            Files.writeString(layout.portableMarker(), "");
            Files.writeString(layout.versionStamp(), obsVersion);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to copy bundled OBS into " + layout.root(), e);
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
        String encoder;
        if (s.encoder == null || s.encoder.isBlank()) {
            // Blank = auto: probe once and persist so the UI (PR4) reflects the choice.
            encoder = encoderProbe.detect();
            s.encoder = encoder;
            settings.save(s);
        } else {
            encoder = s.encoder;
        }
        String recPath =
                (s.videoDir == null || s.videoDir.isBlank())
                        ? paths.videoDir().toString()
                        : s.videoDir;
        String ini =
                loadTemplate(PROFILE_TEMPLATE)
                        .replace("@REC_PATH@", recPath)
                        .replace("@REC_ENCODER@", encoder)
                        .replace("@BASE_CX@", Integer.toString(res[0]))
                        .replace("@BASE_CY@", Integer.toString(res[1]))
                        .replace("@OUT_CX@", Integer.toString(res[0]))
                        .replace("@OUT_CY@", Integer.toString(res[1]));
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
