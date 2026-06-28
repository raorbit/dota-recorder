package dev.dotarec.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads and writes user settings as {@code settings.json} in the data directory.
 *
 * <p>Settings cover the recording resolution, encoder, the VOD retention cap
 * (disk-budget for auto-pruning old recordings), the OBS WebSocket connection
 * (host/port/password), and the video output directory. Missing or unreadable
 * files fall back to defaults so first run always succeeds.
 */
@Component
public class SettingsStore {

    /** Persisted settings shape. Field names map 1:1 to JSON keys. */
    public static class Settings {
        public String resolution = "1920x1080";
        /** Blank = auto: the OBS config writer probes the GPU (HW encoder, x264 fallback). */
        public String encoder = "";
        /**
         * Recording frame rate written into OBS {@code [Video] FPSCommon} (FPSType stays 0, "Common
         * FPS"). An integer OBS common value (UI offers 30/60). Applied on the next OBS launch, like
         * {@link #resolution}.
         */
        public int fps = 60;
        /**
         * OBS Simple-output {@code RecQuality} (case-sensitive). One of Stream/Small/HQ/Lossless; the
         * UI picker offers Stream/HQ/Lossless. Applied on the next OBS launch.
         */
        public String quality = "HQ";
        /**
         * OBS Simple-output {@code RecFormat2} (the recording container). The UI offers the crash-safe
         * subset hybrid_mp4/fragmented_mp4/mkv/mov. Applied on the next OBS launch.
         */
        public String format = "hybrid_mp4";
        /** Disk budget in GiB for the VOD retention/pruning policy. */
        public int retentionCapGb = 50;
        /** OBS WebSocket host; loopback-only for this single-user local app. */
        public String obsHost = "127.0.0.1";
        /**
         * obs-websocket v5 port. We bundle and manage our own OBS on a private 4466
         * (not the 4455 stock default) so it never clashes with a user's own OBS.
         */
        public int obsPort = 4466;
        public String obsPassword = "";
        /** Video output directory; null/blank means use the default video dir. */
        public String videoDir = "";
        /**
         * Dota 2 32-bit account id (the OpenDota {@code account_id}). Used by the enricher to find
         * OUR player in the 10-player scoreboard so result/stats can be attributed. Null until the
         * user configures it; a null id holds matches in {@code pending} rather than failing them.
         */
        public Long accountId = null;
        /**
         * Optional OpenDota API key. When present the enricher appends {@code ?api_key=} to lift the
         * anonymous rate limit. Out of scope for B7 unit tests; the live transport uses it in PR5.
         */
        public String opendotaApiKey = "";
        /**
         * Shared secret written into the GSI {@code auth { token }} block when the cfg is installed and
         * validated on every inbound {@code /gsi} frame. Blank until {@code GsiCfgInstaller} mints one
         * on first install; a blank token means "accept all frames" so a half-configured install never
         * goes dark (the GSI feed only carries a token once a cfg that has one is written).
         */
        public String gsiAuthToken = "";
        /**
         * User-managed audio source list captured into every recording. Each source is one OBS WASAPI
         * input (application/output/input) with a 0–100 volume and a mute toggle. Seeded on
         * {@link #load} with a single Dota application-capture source so a fresh install records the
         * game's audio (and nothing else) out of the box; the user adds more sources (e.g. Discord, a
         * mic) in the UI. The OBS scene configurer reconciles this list into {@code dotarec:<id>}-named
         * inputs.
         *
         * <p>Defaults to {@code null} (NOT an empty list) on purpose: load() seeds the Dota default
         * ONLY when the field is null (a fresh install or a legacy settings.json predating the field),
         * so an explicit empty list the user saved by clearing every source is durable and is NOT
         * re-seeded on the next launch.
         */
        public List<AudioSource> audioSources;

        /** Field-by-field copy (all fields are primitive/immutable) for atomic copy-on-write updates. */
        Settings copy() {
            Settings c = new Settings();
            c.resolution = resolution;
            c.encoder = encoder;
            c.fps = fps;
            c.quality = quality;
            c.format = format;
            c.retentionCapGb = retentionCapGb;
            c.obsHost = obsHost;
            c.obsPort = obsPort;
            c.obsPassword = obsPassword;
            c.videoDir = videoDir;
            c.accountId = accountId;
            c.opendotaApiKey = opendotaApiKey;
            c.gsiAuthToken = gsiAuthToken;
            // Deep-copy the list (records are immutable, so element sharing is safe). Omitting this
            // would silently drop audioSources on every copy-on-write update(). Null-safe: the field
            // defaults to null pre-seed, though copy() is only ever called on post-load settings.
            c.audioSources = audioSources == null ? null : new ArrayList<>(audioSources);
            return c;
        }
    }

    /**
     * One audio source captured into the recording. FROZEN wire shape — serialized identically by core
     * (Jackson) and the renderer (TS). {@code id} is a stable client-or-core-generated UUID used as the
     * React key AND the {@code dotarec:<id>} OBS input-name suffix; {@code kind} is one of
     * {@code application|output|input}; {@code target} selects the device/process ({@code "default"} or
     * a WASAPI device_id / encoded window string, may be null); {@code label} is display-only;
     * {@code volume} is a 0–100 UI percent; {@code muted} toggles mute.
     */
    public record AudioSource(
            String id, String kind, String target, String label, int volume, boolean muted) {}

    private static final Logger log = LoggerFactory.getLogger(SettingsStore.class);

    private static final String FILE_NAME = "settings.json";
    private static final String TMP_SUFFIX = ".tmp";
    private static final String BAK_SUFFIX = ".bak";

    private final Path file;
    private final ObjectMapper mapper;
    private Settings settings;

    public SettingsStore(AppPaths paths) {
        this.file = paths.dataDir().resolve(FILE_NAME);
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.settings = load(paths);
    }

    private Settings load(AppPaths paths) {
        Settings loaded = readOrNull(file);
        if (loaded == null) {
            // Primary file missing or corrupt (e.g. a crash truncated it mid-write). Try the one-deep
            // backup that save() rolls before each replace -- it holds the last good settings, so the
            // user's gsiAuthToken / obsPassword / accountId survive a torn write -- before falling back
            // to fresh defaults.
            Path bak = file.resolveSibling(FILE_NAME + BAK_SUFFIX);
            loaded = readOrNull(bak);
            if (loaded != null) {
                log.warn("settings.json unreadable; recovered from {}", bak.getFileName());
            }
        }
        if (loaded == null) {
            loaded = new Settings();
        }
        if (loaded.videoDir == null || loaded.videoDir.isBlank()) {
            loaded.videoDir = paths.videoDir().toString();
        }
        // A settings.json written before obsHost existed (or with an explicit null) must not
        // leave a null host that would NPE the OBS connect builder; fall back to loopback.
        if (loaded.obsHost == null || loaded.obsHost.isBlank()) {
            loaded.obsHost = "127.0.0.1";
        }
        // Port 0 means "absent / never set"; restore our managed port rather than bind to 0.
        if (loaded.obsPort <= 0) {
            loaded.obsPort = 4466;
        }
        // A legacy settings.json predating fps/quality/format deserializes them to 0/null; backfill
        // the defaults so writeProfile() never substitutes "0"/null into the OBS profile.
        if (loaded.fps <= 0) {
            loaded.fps = 60;
        }
        if (loaded.quality == null || loaded.quality.isBlank()) {
            loaded.quality = "HQ";
        }
        if (loaded.format == null || loaded.format.isBlank()) {
            loaded.format = "hybrid_mp4";
        }
        // Seed one Dota application-capture source ONLY on a genuinely fresh field (null = fresh
        // install or a legacy settings.json predating audioSources) so the game's audio records out of
        // the box. An explicit empty list (the user cleared every source) is left empty and durable.
        // The window match "::dota2.exe" is the encoded "title:class:exe" string the scene configurer
        // pairs with priority=2 (match by executable), so it binds whenever dota2.exe is running.
        if (loaded.audioSources == null) {
            loaded.audioSources =
                    new ArrayList<>(
                            List.of(
                                    new AudioSource(
                                            UUID.randomUUID().toString(),
                                            "application",
                                            "::dota2.exe",
                                            "Dota 2",
                                            100,
                                            false)));
        }
        return loaded;
    }

    /** Reads and parses a settings file, or {@code null} when it is absent, unreadable, or corrupt. */
    private Settings readOrNull(Path path) {
        if (!Files.isReadable(path)) {
            return null;
        }
        try {
            return mapper.readValue(path.toFile(), Settings.class);
        } catch (IOException e) {
            return null;
        }
    }

    /** Current in-memory settings. */
    public synchronized Settings get() {
        return settings;
    }

    /**
     * Replaces settings and persists them to {@code settings.json} ATOMICALLY: serialize to a sibling
     * {@code .tmp}, roll the previous good file to a one-deep {@code .bak}, then atomic-move the temp
     * over the real file. A crash mid-write can only leave the (discardable) temp or the intact old
     * file behind -- never a truncated {@code settings.json} that {@link #load} would silently replace
     * with defaults, dropping the user's gsiAuthToken / obsPassword / accountId. Mirrors the DB
     * pre-migration backup pattern (MigrationRunner).
     */
    public synchronized void save(Settings updated) {
        Path tmp = file.resolveSibling(FILE_NAME + TMP_SUFFIX);
        Path bak = file.resolveSibling(FILE_NAME + BAK_SUFFIX);
        try {
            mapper.writeValue(tmp.toFile(), updated);
            // Roll a one-deep backup of the last good file before clobbering it (overwritten each save).
            if (Files.exists(file)) {
                Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
            }
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
            this.settings = updated;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write settings: " + file, e);
        }
    }

    /**
     * Atomically read-copy-mutate-persist under the store lock. The mutator is handed a private copy
     * of the current settings and returns the version to persist, so a check-then-act stays atomic and
     * concurrent {@link #get()} readers only ever observe the old or new instance whole — never a
     * half-applied state. Prefer this over {@code get()} + field mutation + {@code save()}.
     */
    public synchronized void update(UnaryOperator<Settings> mutator) {
        save(mutator.apply(settings.copy()));
    }
}
