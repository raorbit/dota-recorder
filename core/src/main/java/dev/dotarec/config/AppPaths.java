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
 *
 * <p>The writable OBS install ({@link #obsDir()}) is resolved separately: it is a
 * large, machine-local, regenerable binary tree, so it lives under
 * {@code %LOCALAPPDATA%} (non-roaming) rather than under the Roaming data root.
 */
@Component
public class AppPaths {

    private final Path dataDir;
    private final Path videoDir;
    private final Path dbDir;
    private final Path logDir;
    private final Path obsDir;

    public AppPaths(
            @Value("${app.data-dir:}") String configuredDataDir,
            @Value("${app.obs.dir:}") String configuredObsDir) {
        Path root = resolveRoot(configuredDataDir);
        this.dataDir = ensureDir(root);
        this.videoDir = ensureDir(resolveVideoDir(configuredDataDir));
        this.dbDir = ensureDir(root.resolve("db"));
        this.logDir = ensureDir(root.resolve("log"));
        this.obsDir = ensureDir(resolveObsRoot(configuredObsDir, root));
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

    /**
     * Default recording directory. With an explicitly configured data dir (tests / custom installs)
     * recordings stay isolated under {@code <dataRoot>/video}; otherwise they default to the user's
     * {@code Videos/Dota2Rec} folder so VODs land somewhere obvious and browsable rather than buried in
     * the Roaming app data. The user can override this in settings.
     */
    private static Path resolveVideoDir(String configuredDataDir) {
        if (configuredDataDir != null && !configuredDataDir.isBlank()) {
            return Path.of(configuredDataDir).resolve("video");
        }
        return Path.of(System.getProperty("user.home"), "Videos", "Dota2Rec");
    }

    private static Path resolveObsRoot(String configuredObsDir, Path dataRoot) {
        if (configuredObsDir != null && !configuredObsDir.isBlank()) {
            return Path.of(configuredObsDir);
        }
        // Non-roaming: the writable OBS copy is large and regenerable, so it belongs in
        // LOCALAPPDATA, never Roaming. Electron launches obs64.exe from this same path.
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "dota-recorder", "obs");
        }
        return dataRoot.resolve("obs");
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

    /**
     * Writable OBS install root ({@code %LOCALAPPDATA%/dota-recorder/obs} by default).
     * A first-run copy of the bundled portable OBS lives here; the auto-config writer
     * writes OBS config into it and Electron launches {@code obs64.exe} from it.
     */
    public Path obsDir() {
        return obsDir;
    }
}
