package dev.dotarec.obs.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** Per-process cache of the auto-detected encoder so the (bounded) GPU probe runs at most once per
     * launch; a new process re-probes so a swapped GPU re-adapts. Null until first resolved. */
    private volatile String resolvedAutoEncoder;
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
     * Pins the obs-websocket password and the managed port in a single atomic settings update so the
     * read-modify-write cannot race a concurrent {@code PUT /settings}.
     *
     * <p>The auto-detected encoder is deliberately NOT persisted here: a blank {@code encoder} is the
     * "auto" sentinel, and {@link #resolveEncoder} probes the GPU at profile-write time (cached per
     * process). Persisting the probed token would defeat the sentinel — the UI would then show the
     * resolved encoder as a manual override, and a later GPU swap would never re-probe.
     */
    private void ensureSettings() {
        settings.update(
                s -> {
                    if (s.obsPassword == null || s.obsPassword.isBlank()) {
                        s.obsPassword = generatePassword();
                    }
                    if (s.obsPort <= 0) {
                        s.obsPort = MANAGED_PORT;
                    }
                    return s;
                });
    }

    /**
     * Resolves the {@code RecEncoder} token for the profile. An explicit (non-blank) {@code encoder}
     * is the user's manual override, used as-is. A blank encoder means "auto": the GPU is probed once
     * per process (cached in {@link #resolvedAutoEncoder}) so a swapped GPU re-adapts on the next
     * launch while the UI keeps showing "Auto" (settings.encoder stays blank). Falls back to x264 so
     * the profile never writes an empty RecEncoder that OBS would reject.
     */
    private String resolveEncoder(SettingsStore.Settings s) {
        if (s.encoder != null && !s.encoder.isBlank()) {
            return s.encoder;
        }
        String cached = resolvedAutoEncoder;
        if (cached == null) {
            cached = encoderProbe.detect();
            if (cached == null || cached.isBlank()) {
                cached = EncoderProbe.X264;
            }
            resolvedAutoEncoder = cached;
        }
        return cached;
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
        // Resolve the encoder for the profile: the user's manual choice, or the auto-probed GPU encoder
        // when blank ("auto"). The blank sentinel is never persisted, so the UI keeps showing Auto and a
        // GPU swap re-probes next launch; this never mutates/saves settings.
        String encoder = resolveEncoder(s);
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
        // Substitute every @TOKEN@ in a SINGLE regex pass so an inserted value is never re-scanned as
        // a template token. @REC_PATH@ is user-controlled and can legitimately contain another token's
        // literal text (e.g. D:\clips\@FPS@\dota); an ordered chain of String.replace would let that
        // later replace corrupt the path. appendReplacement over the token regex substitutes each match
        // exactly once from the map (quoteReplacement so a value's $/\ are not treated as $-group refs).
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("REC_PATH", recPathIni);
        tokens.put("REC_ENCODER", encoder);
        tokens.put("BASE_CX", Integer.toString(res[0]));
        tokens.put("BASE_CY", Integer.toString(res[1]));
        tokens.put("OUT_CX", Integer.toString(res[0]));
        tokens.put("OUT_CY", Integer.toString(res[1]));
        // FPS/quality/format are user settings (defaults backfilled in SettingsStore.load).
        // Guard null/blank defensively in case a legacy settings.json bypassed the backfill.
        tokens.put("FPS", Integer.toString(s.fps > 0 ? s.fps : 60));
        tokens.put("REC_QUALITY", (s.quality == null || s.quality.isBlank()) ? "HQ" : s.quality);
        tokens.put("REC_FORMAT", (s.format == null || s.format.isBlank()) ? "hybrid_mp4" : s.format);
        String ini = substituteTokens(loadTemplate(PROFILE_TEMPLATE), tokens);
        writeAtomically(layout.profileIni(), ini);
    }

    /** Matches a {@code @TOKEN@} placeholder ({@code A-Z}, {@code 0-9}, {@code _}) in the template. */
    private static final Pattern TOKEN = Pattern.compile("@([A-Z0-9_]+)@");

    /**
     * Replaces each {@code @TOKEN@} in {@code template} with its mapped value in ONE pass over the
     * input, so an inserted value can never be re-interpreted as a placeholder. An unknown token (not
     * in {@code values}) is left verbatim rather than blanked.
     */
    private static String substituteTokens(String template, Map<String, String> values) {
        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, value == null ? Matcher.quoteReplacement(m.group()) : Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Writes {@code content} to {@code file} ATOMICALLY: serialize to a sibling {@code .tmp} in the
     * same directory, then atomic-move it over the target (falling back to a plain replace where the
     * filesystem rejects an atomic move). A failure mid-write can only leave the discardable temp or
     * the intact previous file — never a truncated {@code basic.ini}, which OBS would reject as a bad
     * profile so it would never emit OUTPUT_STARTED. Mirrors {@link SettingsStore#save}.
     */
    private static void writeAtomically(Path file, String content) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(
                        tmp,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Rare (same-dir NTFS supports atomic move); fall back to a plain replace.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write OBS profile: " + file, e);
        }
    }

    /** Parses {@code "1920x1080"} -> {@code {1920, 1080}}; falls back to 1080p on anything odd. */
    public static int[] parseResolution(String resolution) {
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
