package dev.dotarec.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the ffmpeg executable the core uses for video post-processing.
 *
 * <p>The Electron supervisor bundles a static {@code ffmpeg.exe} and threads its path into the core
 * as the {@code app.ffmpeg.path} system property (with env {@code DOTAREC_FFMPEG_PATH} as a
 * belt-and-suspenders fallback), so a packaged install never depends on ffmpeg being on the user's
 * PATH. A dev run gets the same bundled binary; a bare {@code java -jar} with neither configured
 * falls back to {@code ffmpeg} on PATH.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code app.ffmpeg.path} — used only if non-blank AND the file exists,
 *   <li>env {@code DOTAREC_FFMPEG_PATH} — used only if it exists,
 *   <li>bare {@code "ffmpeg"} — assumed resolvable on PATH (presence not verified here).
 * </ol>
 */
@Component
public class FfmpegLocator {

    /** Env var fallback for the ffmpeg path; mirrors {@code DOTAREC_FFMPEG_PATH} in the supervisor. */
    public static final String FFMPEG_PATH_ENV = "DOTAREC_FFMPEG_PATH";

    private static final Logger log = LoggerFactory.getLogger(FfmpegLocator.class);

    private final String configured;

    public FfmpegLocator(@Value("${app.ffmpeg.path:}") String configured) {
        this.configured = configured;
    }

    /**
     * Resolve a bundled/configured ffmpeg executable that exists on disk.
     *
     * @return the resolved {@link Path} from {@code app.ffmpeg.path} or {@code DOTAREC_FFMPEG_PATH}
     *     when present, or {@link Optional#empty()} when neither is set/exists (caller may then fall
     *     back to a bare {@code "ffmpeg"} on PATH).
     */
    public Optional<Path> resolve() {
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            if (Files.isRegularFile(p)) {
                log.info("Using configured ffmpeg: {}", p);
                return Optional.of(p);
            }
            log.warn("Configured ffmpeg path does not exist, ignoring: {}", configured);
        }

        String fromEnv = System.getenv(FFMPEG_PATH_ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            Path p = Path.of(fromEnv);
            if (Files.isRegularFile(p)) {
                log.info("Using ffmpeg from {}: {}", FFMPEG_PATH_ENV, p);
                return Optional.of(p);
            }
            log.warn("{} points at a missing file, ignoring: {}", FFMPEG_PATH_ENV, fromEnv);
        }

        log.debug("No bundled ffmpeg configured; falling back to bare \"ffmpeg\" on PATH.");
        return Optional.empty();
    }

    /**
     * The ffmpeg command the core should invoke: the resolved bundled/configured path when present,
     * else the bare {@code "ffmpeg"} executable name (assumed on PATH).
     */
    public String command() {
        return resolve().map(Path::toString).orElse("ffmpeg");
    }

    /**
     * Whether a bundled/configured ffmpeg was located on disk. {@code false} means the core will rely
     * on a {@code "ffmpeg"} on PATH, whose presence this class does not verify.
     */
    public boolean isAvailable() {
        return resolve().isPresent();
    }
}
