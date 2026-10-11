package dev.dotarec.obs;

import io.obswebsocket.community.client.OBSRemoteController;
import io.obswebsocket.community.client.message.response.scenes.GetCurrentProgramSceneResponse;
import io.obswebsocket.community.client.message.response.sources.SaveSourceScreenshotResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The current OBS program scene: looking it up, and screenshotting it into a throwaway temp file.
 * The one implementation behind {@link ObsController#isRenderBroken()} (can OBS draw at all?) and
 * {@link CaptureBlackFrameGuard#sampleProgramSceneLuma()} (is what it draws black?), whose copies had
 * already drifted (one let a failed scene lookup escape, only one checked for an empty file).
 * {@link ThumbnailService} shares only the lookup: its screenshot goes to a persistent path and a
 * failure there is an error to throw, not a verdict to read.
 *
 * <p>Shoots the SCENE, never the Game Capture source: an unhooked capture source is 0x0 and OBS
 * refuses to screenshot it, whereas a scene is always canvas-sized and renders (black when nothing
 * is hooked). So on a healthy OBS a scene screenshot always succeeds, which is what lets a refusal
 * mean "the renderer is gone" (see {@link CaptureBlackFrameGuard#sampleProgramSceneLuma()}).
 *
 * <p>The callers read a failure differently, so {@link Outcome} says WHICH step stopped a screenshot
 * instead of collapsing everything into one null: OBS having no active scene says nothing about its
 * renderer (the render probe must not restart OBS over it, the guard must not call it black), while
 * OBS refusing a scene it does have -- an unsuccessful or missing response (the library returns null
 * on timeout) or an empty file -- is the lost-graphics-device signature. Exceptions propagate so each
 * caller keeps its own "unknown, never broken/black" handling, and the temp file is deleted on every
 * path, including when the caller's reader throws.
 *
 * <p>Timeouts, sizes, format and temp prefix stay the caller's: the render probe runs under the
 * {@code MatchFsm} monitor and must fit the short readiness budget at a token size, while the
 * off-thread black-frame guard can afford the full request timeout and a sample big enough to average.
 */
final class ProgramScene {

    /** -1 lets OBS pick the codec's default quality. */
    private static final int COMPRESSION_DEFAULT = -1;

    /** How far a {@link #screenshot} got. */
    enum Outcome {
        /** OBS has no active program scene: nothing to shoot, and nothing learned about rendering. */
        NO_SCENE,
        /** OBS has a scene but refused to save it, or saved an empty file: it can't render. */
        REFUSED,
        /** OBS saved a non-empty image and the caller's reader ran against it. */
        SAVED
    }

    /** Reads the saved image while its temp file still exists. */
    @FunctionalInterface
    interface ImageReader<T> {
        T read(Path image) throws IOException;
    }

    /** A screenshot's {@link Outcome}, plus what the reader made of the image (null unless SAVED). */
    record Shot<T>(Outcome outcome, T value) {}

    private ProgramScene() {}

    /**
     * The active program scene's name, or {@code null} when OBS has none or didn't answer within
     * {@code timeoutMs}. A blank name counts as none.
     */
    static String currentName(OBSRemoteController c, long timeoutMs) {
        GetCurrentProgramSceneResponse scene = c.getCurrentProgramScene(timeoutMs);
        if (scene == null || !scene.isSuccessful()) {
            return null;
        }
        String name = scene.getCurrentProgramSceneName();
        return name == null || name.isBlank() ? null : name;
    }

    /**
     * {@link #screenshot(OBSRemoteController, String, String, int, int, long, ImageReader)} for a
     * caller that only needs the outcome, not the image.
     */
    static Outcome screenshot(
            OBSRemoteController c,
            String tempPrefix,
            String format,
            int width,
            int height,
            long timeoutMs)
            throws IOException {
        return screenshot(c, tempPrefix, format, width, height, timeoutMs, image -> null).outcome();
    }

    /**
     * Screenshots the active program scene at {@code width}x{@code height} into a temp file named
     * {@code <tempPrefix>*.<format>}, hands a non-empty result to {@code reader}, and deletes the file.
     * Both obs-websocket calls (the scene lookup and the screenshot) are bounded by {@code timeoutMs}.
     *
     * @throws IOException if the temp file can't be created or measured, or the reader fails
     */
    static <T> Shot<T> screenshot(
            OBSRemoteController c,
            String tempPrefix,
            String format,
            int width,
            int height,
            long timeoutMs,
            ImageReader<T> reader)
            throws IOException {
        String sceneName = currentName(c, timeoutMs);
        if (sceneName == null) {
            return new Shot<>(Outcome.NO_SCENE, null);
        }
        Path tmp = Files.createTempFile(tempPrefix, "." + format);
        try {
            SaveSourceScreenshotResponse resp =
                    c.saveSourceScreenshot(
                            sceneName,
                            format,
                            tmp.toString(),
                            width,
                            height,
                            COMPRESSION_DEFAULT,
                            timeoutMs);
            if (resp == null || !resp.isSuccessful() || Files.size(tmp) == 0L) {
                return new Shot<>(Outcome.REFUSED, null);
            }
            return new Shot<>(Outcome.SAVED, reader.read(tmp));
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // Temp file cleanup is best-effort.
            }
        }
    }
}
