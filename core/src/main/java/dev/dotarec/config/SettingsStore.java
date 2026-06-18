package dev.dotarec.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        public String encoder = "x264";
        /** Disk budget in GiB for the VOD retention/pruning policy. */
        public int retentionCapGb = 50;
        /** OBS WebSocket host; loopback-only for this single-user local app. */
        public String obsHost = "127.0.0.1";
        /** OBS WebSocket v5 server port; OBS default is 4455 (v4 used 4444). */
        public int obsPort = 4455;
        public String obsPassword = "";
        /** Video output directory; null/blank means use the default video dir. */
        public String videoDir = "";
    }

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
        // Port 0 means "absent / never set"; restore the OBS v5 default rather than bind to 0.
        if (loaded.obsPort <= 0) {
            loaded.obsPort = 4455;
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
}
