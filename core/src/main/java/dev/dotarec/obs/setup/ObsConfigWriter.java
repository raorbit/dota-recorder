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
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
public class ObsConfigWriter implements EncoderFallback {

    private static final Logger log = LoggerFactory.getLogger(ObsConfigWriter.class);

    private static final int MANAGED_PORT = 4466;
    private static final String PROFILE_TEMPLATE = "obs/profile-basic.ini";

    private final AppPaths paths;
    private final SettingsStore settings;
    private final EncoderProbe encoderProbe;
    private final String sourceDir;
    private final String obsVersion;

    /** Per-process cache of the auto-detected encoder so the (bounded) GPU probe runs at most once per
     * launch; a new process re-probes so a swapped GPU re-adapts. Null until first resolved, and x264
     * once {@link #fallBackToX264} found that OBS can't open the probed one. */
    private volatile String resolvedAutoEncoder;
    private final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Free bytes on the drive holding a folder, for this user. Seam for tests. */
    interface UsableSpace {
        long usableBytes(Path dir) throws IOException;
    }

    /**
     * Reads the free space OBS checks before a record start (see {@link #folderRefusal}); a test
     * swaps it to fake a nearly full drive.
     */
    private UsableSpace usableSpace = dir -> Files.getFileStore(dir).getUsableSpace();

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
        // AFTER the profile and best-effort (it never throws): the profile is what records; the
        // first-run flag only keeps a wizard from opening, so its failure must not cost the profile.
        markFirstRunDone(layout);
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

    /**
     * Switches the auto-picked encoder to x264 for the rest of this process and re-writes
     * {@code basic.ini}, for a hardware encoder OBS offers but can't open. OBS offers QSV on any
     * Intel GPU, and AMF after a check that never opens an encode session, while its own x264
     * fallback only replaces an encoder it didn't register. When the one it offers then fails to
     * open, every record start shows an error dialog in OBS and never starts the output, restart or
     * not; some drivers crash OBS there instead. {@link dev.dotarec.fsm.MatchFsm} calls this once a
     * second run of OBS in a row fails that way, then restarts OBS so the relaunched one loads x264,
     * or as soon as an OBS crashed while it started recording, leaving it to Electron's relaunches
     * to load x264. The profile names the one token as both the recording and the streaming encoder
     * (the default Stream quality records with the latter), so both switch.
     *
     * <p>Nothing is switched while the profile's video folder is missing (an unplugged drive), or
     * its drive has under {@link #OBS_MIN_FREE_BYTES} free. OBS turns a start down for either before
     * it opens any encoder, on every run, so it looks just like an encoder that can't open; switching
     * then would only CPU-encode every match for the rest of the process once the folder is back.
     * That is logged instead, and the next call checks again. A folder that exists but OBS can't
     * write into (read-only media, changed permissions, Controlled Folder Access) is not detected:
     * OBS opens the encoder before it fails to create the file, so those starts still count toward
     * the switch.
     *
     * <p>Only "auto" is switched. An encoder picked in Settings is the user's: it is kept, and the
     * failure logged. Settings are not touched, so the UI keeps showing Auto, and the next launch
     * probes the GPU again (a driver update may have fixed the encoder by then).
     *
     * @return true when the profile now names x264 where it named a hardware encoder; false when
     *     there was nothing to switch, the video folder is missing or its drive nearly full, or the
     *     profile could not be written (the probed encoder is then kept, so the next call tries again)
     */
    @Override
    public synchronized boolean fallBackToX264() {
        SettingsStore.Settings s = settings.get();
        String folderRefusal = folderRefusal(recordingFolder(s));
        if (folderRefusal != null) {
            log.warn("Not switching the OBS encoder to x264: {}", folderRefusal);
            return false;
        }
        if (s.encoder != null && !s.encoder.isBlank()) {
            if (!EncoderProbe.X264.equals(s.encoder)) {
                log.warn(
                        "OBS fails to start recording with the {} encoder picked in Settings;"
                                + " keeping it. Set the encoder to Auto or x264 if matches stop"
                                + " recording",
                        s.encoder);
            }
            return false;
        }
        String probed = resolveEncoder(s);
        if (EncoderProbe.X264.equals(probed)) {
            // Already on the encoder every PC can run: there is nothing left to switch to.
            return false;
        }
        resolvedAutoEncoder = EncoderProbe.X264;
        try {
            writeProfile(new ObsLayout(paths.obsDir()));
        } catch (RuntimeException e) {
            // Keep the probed encoder: with x264 cached, the next call would find nothing left to
            // switch while basic.ini still named the encoder OBS can't open.
            resolvedAutoEncoder = probed;
            log.warn("Could not switch the OBS profile from {} to x264: {}", probed, e.toString());
            return false;
        }
        log.warn(
                "Switching the OBS encoder from the auto-picked {} to x264 until the app restarts:"
                        + " OBS fails to start recording with it (an encoder it offers but can't"
                        + " open, e.g. on an outdated GPU driver)",
                probed);
        return true;
    }

    /**
     * OBS 32.1.2 turns a record start down when the folder's drive has less than this free: 50 MB
     * ({@code MBYTES_LEFT_STOP_REC}, in MiB, in OBSBasic_Recording.cpp).
     */
    static final long OBS_MIN_FREE_BYTES = 50L * 1024 * 1024;

    /**
     * Why OBS would turn down a record start into {@code folder} before it opens any encoder, or
     * null when it wouldn't, or when that can't be told. OBS 32.1.2's StartRecording checks two
     * things first: that the folder exists ({@code OutputPathValid}, which asks
     * {@code QDir(path).exists()}), and that its drive has at least {@link #OBS_MIN_FREE_BYTES}
     * free to this user ({@code LowDiskSpace}, which reads what {@link FileStore#getUsableSpace()}
     * reads). Either failing shows an error dialog in OBS and starts nothing. A folder that exists
     * but can't be written to passes both, and nothing here tries a write: OBS fails that start only
     * after it opened the encoders. A check that can't be made here blames nothing, so a broken
     * encoder is still switched.
     */
    private String folderRefusal(String folder) {
        Path dir;
        try {
            dir = Path.of(folder);
        } catch (InvalidPathException e) {
            return null;
        }
        if (!Files.isDirectory(dir)) {
            return "the video folder " + folder + " doesn't exist (is its drive unplugged?), and OBS"
                    + " won't record into a missing folder, whatever the encoder";
        }
        long free;
        try {
            free = usableSpace.usableBytes(dir);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read the free space for {}: {}", folder, e.toString());
            return null;
        }
        if (free < OBS_MIN_FREE_BYTES) {
            return "the drive holding the video folder " + folder + " has " + free / (1024 * 1024)
                    + " MB free, and OBS won't record with under 50 MB free, whatever the encoder";
        }
        return null;
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

    /**
     * Sets {@code [General] FirstRun=true} in OBS's {@code user.ini} so a fresh install never opens the
     * Auto-Configuration Wizard. OBS disables every output while that wizard is open, and our OBS
     * lives in the tray, so an unnoticed wizard silently swallowed every StartRecord: OBS accepted the
     * request, logged nothing, and never emitted OUTPUT_STARTED.
     *
     * <p>Merges rather than overwrites: OBS keeps its own frontend state in the same file.
     *
     * <p>Best-effort: a failure is logged, never thrown. This used to throw and ran BEFORE
     * {@link #writeProfile}, so a non-essential edit (e.g. an AV scanner briefly locking a just-created
     * {@code user.ini} on first run) aborted {@link #configure} before {@code basic.ini} (encoder,
     * output path) was written, and {@link ObsConfigBootstrap} never marked readiness: a cosmetic
     * write blocked all recording. A missed flag costs at most the wizard on that launch, and the next
     * startup's configure() retries it. Catches both the read's {@link IOException} and the
     * {@link UncheckedIOException} that {@link #writeAtomically} throws.
     */
    private static void markFirstRunDone(ObsLayout layout) {
        Path file = layout.userIni();
        try {
            String current = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            String updated = withFirstRunDone(current);
            if (!updated.equals(current)) {
                writeAtomically(file, updated);
            }
        } catch (IOException | UncheckedIOException e) {
            log.warn(
                    "Could not set FirstRun in OBS user config {}; OBS's Auto-Configuration Wizard may"
                            + " open on first launch and hold recording until dismissed: {}",
                    file,
                    e.toString());
        }
    }

    /**
     * Returns {@code ini} with {@code FirstRun=true} in its {@code [General]} section: an existing
     * {@code FirstRun} line is replaced, otherwise one is added under the header, and a missing
     * section is prepended. Keeps OBS's UTF-8 byte-order mark and line endings. Package-visible for
     * tests.
     */
    static String withFirstRunDone(String ini) {
        String bom = ini.startsWith("\uFEFF") ? "\uFEFF" : "";
        String body = ini.substring(bom.length());
        String eol = body.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(body.split("\r?\n", -1)));
        int general = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equalsIgnoreCase("[General]")) {
                general = i;
                break;
            }
        }
        if (general < 0) {
            String rest = body.isEmpty() ? "" : eol + body;
            return bom + "[General]" + eol + "FirstRun=true" + eol + rest;
        }
        for (int i = general + 1; i < lines.size() && !lines.get(i).trim().startsWith("["); i++) {
            if (lines.get(i).trim().startsWith("FirstRun=")) {
                lines.set(i, "FirstRun=true");
                return bom + String.join(eol, lines);
            }
        }
        lines.add(general + 1, "FirstRun=true");
        return bom + String.join(eol, lines);
    }

    private void writeProfile(ObsLayout layout) {
        SettingsStore.Settings s = settings.get();
        int[] res = parseResolution(s.resolution);
        // Resolve the encoder for the profile: the user's manual choice, or the auto-probed GPU encoder
        // when blank ("auto"). The blank sentinel is never persisted, so the UI keeps showing Auto and a
        // GPU swap re-probes next launch; this never mutates/saves settings.
        String encoder = resolveEncoder(s);
        String recPath = recordingFolder(s);
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
        // The template writes the encoder as BOTH RecEncoder and StreamEncoder: at RecQuality=Stream
        // (the default) OBS records with the streaming encoder and never reads RecEncoder, so without
        // StreamEncoder the probe or the user's pick was ignored and OBS's default encoder ran.
        tokens.put("REC_ENCODER", encoder);
        tokens.put("BASE_CX", Integer.toString(res[0]));
        tokens.put("BASE_CY", Integer.toString(res[1]));
        tokens.put("OUT_CX", Integer.toString(res[0]));
        tokens.put("OUT_CY", Integer.toString(res[1]));
        // FPS/quality/format are user settings (defaults backfilled in SettingsStore.load).
        // Guard null/blank defensively in case a legacy settings.json bypassed the backfill.
        tokens.put("FPS", Integer.toString(s.fps > 0 ? s.fps : 30));
        tokens.put("REC_QUALITY", (s.quality == null || s.quality.isBlank()) ? "Stream" : s.quality);
        tokens.put("REC_FORMAT", (s.format == null || s.format.isBlank()) ? "hybrid_mp4" : s.format);
        String ini = substituteTokens(loadTemplate(PROFILE_TEMPLATE), tokens);
        writeAtomically(layout.profileIni(), ini);
    }

    /** The folder the profile has OBS record into: the one set in Settings, else the default. */
    private String recordingFolder(SettingsStore.Settings s) {
        return (s.videoDir == null || s.videoDir.isBlank())
                ? paths.videoDir().toString()
                : s.videoDir;
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
            throw new UncheckedIOException("Failed to write OBS config: " + file, e);
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
