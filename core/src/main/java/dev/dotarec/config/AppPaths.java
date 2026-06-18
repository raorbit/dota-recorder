package dev.dotarec.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves and creates the application data directories.
 *
 * <p>The data root is {@code %APPDATA%/dota-recorder} by default (overridable via
 * {@code app.data-dir}), with {@code data}, {@code video}, {@code db} and
 * {@code log} subdirectories created on startup. If {@code APPDATA} is unset
 * (non-standard environment / tests) it falls back to
 * {@code ~/.dota-recorder} so resolution never fails.
 */
@Component
public class AppPaths {

    private final Path dataDir;
    private final Path videoDir;
    private final Path dbDir;
    private final Path logDir;

    public AppPaths(@Value("${app.data-dir:}") String configuredDataDir) {
        Path root = resolveRoot(configuredDataDir);
        this.dataDir = ensureDir(root);
        this.videoDir = ensureDir(root.resolve("video"));
        this.dbDir = ensureDir(root.resolve("db"));
        this.logDir = ensureDir(root.resolve("log"));
    }

    private static Path resolveRoot(String configuredDataDir) {
        if (configuredDataDir != null && !configuredDataDir.isBlank()) {
            return Path.of(configuredDataDir);
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "dota-recorder");
        }
        return Path.of(System.getProperty("user.home"), ".dota-recorder");
    }

    private static Path ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create app directory: " + dir, e);
        }
    }

    /** Root data directory ({@code %APPDATA%/dota-recorder}). */
    public Path dataDir() {
        return dataDir;
    }

    /** Default location for recorded .mp4 files (user may override in settings). */
    public Path videoDir() {
        return videoDir;
    }

    /** Directory holding the SQLite database file and its pre-migration backups. */
    public Path dbDir() {
        return dbDir;
    }

    /** Directory for the rotating core log (findable for troubleshooting). */
    public Path logDir() {
        return logDir;
    }
}
