package dev.dotarec.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
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
         */
        public java.util.List<AudioSource> audioSources = new java.util.ArrayList<>();

        /** Field-by-field copy (all fields are primitive/immutable) for atomic copy-on-write updates. */
        Settings copy() {
            Settings c = new Settings();
            c.resolution = resolution;
            c.encoder = encoder;
            c.retentionCapGb = retentionCapGb;
            c.obsHost = obsHost;
            c.obsPort = obsPort;
            c.obsPassword = obsPassword;
            c.videoDir = videoDir;
            c.accountId = accountId;
            c.opendotaApiKey = opendotaApiKey;
            c.gsiAuthToken = gsiAuthToken;
            // Deep-copy the list (records are immutable, so element sharing is safe). Omitting this
            // would silently drop audioSources on every copy-on-write update().
            c.audioSources = new java.util.ArrayList<>(audioSources);
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

    private static final String FILE_NAME = "settings.json";

    private final Path file;
    private final ObjectMapper mapper;
    private Settings settings;

    public SettingsStore(AppPaths paths) {
        this.file = paths.dataDir().resolve(FILE_NAME);
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.settings = load(paths);
    }

    private Settings load(AppPaths paths) {
        Settings loaded;
        if (Files.isReadable(file)) {
            try {
                loaded = mapper.readValue(file.toFile(), Settings.class);
            } catch (IOException e) {
                // Corrupt/partial file: fall back to defaults rather than crash.
                loaded = new Settings();
            }
        } else {
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
        // Fresh install (or a legacy settings.json predating audioSources, which deserializes to
        // null/empty): seed one Dota application-capture source so a fresh install records the game's
        // audio (and only the game) out of the box. The window match "::dota2.exe" is the encoded
        // "title:class:exe" string the scene configurer pairs with priority=2 (match by executable), so
        // it binds whenever dota2.exe is running. This replaces the old implicit "Desktop Audio" add.
        if (loaded.audioSources == null || loaded.audioSources.isEmpty()) {
            loaded.audioSources =
                    new java.util.ArrayList<>(
                            java.util.List.of(
                                    new AudioSource(
                                            java.util.UUID.randomUUID().toString(),
                                            "application",
                                            "::dota2.exe",
                                            "Dota 2",
                                            100,
                                            false)));
        }
        return loaded;
    }

    /** Current in-memory settings. */
    public synchronized Settings get() {
        return settings;
    }

    /** Replaces settings and persists them to {@code settings.json}. */
    public synchronized void save(Settings updated) {
        try {
            mapper.writeValue(file.toFile(), updated);
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
