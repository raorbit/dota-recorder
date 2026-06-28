package dev.dotarec.obs;

import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import io.obswebsocket.community.client.OBSRemoteController;
import io.obswebsocket.community.client.message.response.scenes.GetCurrentProgramSceneResponse;
import io.obswebsocket.community.client.message.response.sources.SaveSourceScreenshotResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Captures a JPEG thumbnail of the live OBS program scene to {@code <videoDir>/thumbs/<id>.jpg}.
 *
 * <p>IMPORTANT (caller contract): invoke {@link #captureCurrentScene(String)} BEFORE
 * {@link ObsController#stopRecording()}. obs-websocket's SaveSourceScreenshot renders the source's
 * current pixels; once the recording stops and the scene goes idle the capture comes back BLACK, so
 * the thumbnail must be grabbed while the scene is still live. The FSM's stop sequence is therefore:
 * {@code thumbnail -> stopRecording}.
 *
 * <p>This uses OBS's own SaveSourceScreenshot request -- no ffmpeg, no extra binary. A scene is a
 * valid screenshot source, so we shoot the current program scene directly.
 */
@Service
public class ThumbnailService implements ThumbnailCapturer {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);

    private static final long REQUEST_TIMEOUT_MS = 5_000L;
    private static final String IMAGE_FORMAT = "jpg";
    /** 16:9 thumbnail; OBS scales the program scene into it. Width <= 4096 per the v5 spec. */
    private static final int THUMB_WIDTH = 480;
    private static final int THUMB_HEIGHT = 270;
    /** -1 lets OBS pick the codec default quality. */
    private static final int COMPRESSION_DEFAULT = -1;

    private final ObsController obs;
    private final SettingsStore settings;
    private final AppPaths paths;

    public ThumbnailService(ObsController obs, SettingsStore settings, AppPaths paths) {
        this.obs = obs;
        this.settings = settings;
        this.paths = paths;
    }

    /**
     * Saves a thumbnail of the current OBS program scene.
     *
     * <p>Must be called while the recording/scene is still live (before stopRecording), otherwise
     * the image is black.
     *
     * @param id identifier used for the file name (e.g. the recording surrogate id or match id)
     * @return the absolute path the thumbnail was written to
     * @throws ObsException if OBS is not connected, has no active scene, or the save fails
     */
    @Override
    public Path captureCurrentScene(String id) {
        OBSRemoteController controller = obs.controller();
        if (controller == null) {
            throw new ObsException("Cannot capture thumbnail: OBS is not connected");
        }

        GetCurrentProgramSceneResponse scene = controller.getCurrentProgramScene(REQUEST_TIMEOUT_MS);
        if (scene == null
                || !scene.isSuccessful()
                || scene.getCurrentProgramSceneName() == null
                || scene.getCurrentProgramSceneName().isBlank()) {
            throw new ObsException("Cannot capture thumbnail: no active OBS program scene");
        }
        String sceneName = scene.getCurrentProgramSceneName();

        Path out = thumbPathFor(id);
        ensureParent(out);

        SaveSourceScreenshotResponse resp =
                controller.saveSourceScreenshot(
                        sceneName,
                        IMAGE_FORMAT,
                        out.toString(),
                        THUMB_WIDTH,
                        THUMB_HEIGHT,
                        COMPRESSION_DEFAULT,
                        REQUEST_TIMEOUT_MS);
        if (resp == null || !resp.isSuccessful()) {
            throw new ObsException(
                    "OBS SaveSourceScreenshot failed for scene '" + sceneName + "'");
        }
        log.info("Saved thumbnail for {} -> {}", id, out);
        return out;
    }

    /** {@code <videoDir>/thumbs/<id>.jpg}. */
    Path thumbPathFor(String id) {
        return videoDir().resolve("thumbs").resolve(id + "." + IMAGE_FORMAT);
    }

    private Path videoDir() {
        String dir = settings.get().videoDir;
        // Mirror ObsConfigWriter's blank-fallback to the default video dir, so a blank/absent setting
        // can't drop the thumbnail into Path.of("") (the OBS process working directory).
        return (dir == null || dir.isBlank()) ? paths.videoDir() : Path.of(dir);
    }

    private void ensureParent(Path file) {
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create thumbnail directory", e);
        }
    }
}
