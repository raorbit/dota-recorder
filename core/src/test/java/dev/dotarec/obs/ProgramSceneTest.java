package dev.dotarec.obs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.obswebsocket.community.client.OBSRemoteController;
import io.obswebsocket.community.client.message.response.scenes.GetCurrentProgramSceneResponse;
import io.obswebsocket.community.client.message.response.sources.SaveSourceScreenshotResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link ProgramScene}: the three outcomes the render probe and the black-frame
 * guard tell apart (no scene / refused / saved), the temp file being gone on every path, and the
 * caller's timeout, size and prefix reaching OBS -- all against a mocked {@link OBSRemoteController}.
 */
class ProgramSceneTest {

    private static final long TIMEOUT_MS = 1_234L;
    private static final String PREFIX = "dotarec-test-shot-";

    private static OBSRemoteController obsWithScene(String sceneName) {
        OBSRemoteController obs = mock(OBSRemoteController.class);
        GetCurrentProgramSceneResponse scene = mock(GetCurrentProgramSceneResponse.class);
        when(scene.isSuccessful()).thenReturn(true);
        when(scene.getCurrentProgramSceneName()).thenReturn(sceneName);
        when(obs.getCurrentProgramScene(anyLong())).thenReturn(scene);
        return obs;
    }

    /**
     * Stubs SaveSourceScreenshot to write {@code bytes} where it was asked to and answer with
     * {@code response} (null models the library's timeout). Returns the path OBS was handed.
     */
    private static AtomicReference<Path> answerScreenshot(
            OBSRemoteController obs, SaveSourceScreenshotResponse response, byte[] bytes) {
        AtomicReference<Path> requested = new AtomicReference<>();
        when(obs.saveSourceScreenshot(
                        anyString(), anyString(), anyString(), any(), any(), any(), anyLong()))
                .thenAnswer(
                        inv -> {
                            Path p = Path.of(inv.getArgument(2, String.class));
                            requested.set(p);
                            Files.write(p, bytes);
                            return response;
                        });
        return requested;
    }

    private static SaveSourceScreenshotResponse response(boolean successful) {
        SaveSourceScreenshotResponse resp = mock(SaveSourceScreenshotResponse.class);
        when(resp.isSuccessful()).thenReturn(successful);
        return resp;
    }

    @Test
    void noActiveScene_isNoScene_andShootsNothing() throws IOException {
        OBSRemoteController blank = obsWithScene(" ");
        assertThat(ProgramScene.screenshot(blank, PREFIX, "png", 64, 36, TIMEOUT_MS))
                .isEqualTo(ProgramScene.Outcome.NO_SCENE);
        verify(blank, never())
                .saveSourceScreenshot(
                        anyString(), anyString(), anyString(), any(), any(), any(), anyLong());

        // A lookup that timed out (the library answers null) is "no scene" too, not a refusal: the
        // render probe must not read an unanswered lookup as a dead renderer.
        OBSRemoteController unanswered = mock(OBSRemoteController.class);
        when(unanswered.getCurrentProgramScene(anyLong())).thenReturn(null);
        assertThat(ProgramScene.screenshot(unanswered, PREFIX, "png", 64, 36, TIMEOUT_MS))
                .isEqualTo(ProgramScene.Outcome.NO_SCENE);
        assertThat(ProgramScene.currentName(unanswered, TIMEOUT_MS)).isNull();
    }

    @Test
    void unsuccessfulScreenshot_isRefused_skipsTheReader_andDeletesTheTempFile() throws IOException {
        OBSRemoteController obs = obsWithScene("Dota");
        AtomicReference<Path> requested = answerScreenshot(obs, response(false), new byte[0]);
        AtomicBoolean readerRan = new AtomicBoolean();

        ProgramScene.Shot<String> shot =
                ProgramScene.screenshot(
                        obs, PREFIX, "png", 64, 36, TIMEOUT_MS,
                        image -> {
                            readerRan.set(true);
                            return "read";
                        });

        assertThat(shot.outcome()).isEqualTo(ProgramScene.Outcome.REFUSED);
        assertThat(shot.value()).isNull();
        assertThat(readerRan).isFalse();
        assertThat(requested.get()).doesNotExist();
    }

    @Test
    void screenshotTimeout_isRefused() throws IOException {
        // The library answers null on timeout: OBS has a scene yet didn't draw it in time.
        OBSRemoteController obs = obsWithScene("Dota");
        AtomicReference<Path> requested = answerScreenshot(obs, null, new byte[0]);

        assertThat(ProgramScene.screenshot(obs, PREFIX, "png", 64, 36, TIMEOUT_MS))
                .isEqualTo(ProgramScene.Outcome.REFUSED);
        assertThat(requested.get()).doesNotExist();
    }

    @Test
    void successfulButEmptyFile_isRefused() throws IOException {
        // OBS said yes but wrote nothing: no frame came out of the renderer.
        OBSRemoteController obs = obsWithScene("Dota");
        AtomicReference<Path> requested = answerScreenshot(obs, response(true), new byte[0]);

        assertThat(ProgramScene.screenshot(obs, PREFIX, "png", 64, 36, TIMEOUT_MS))
                .isEqualTo(ProgramScene.Outcome.REFUSED);
        assertThat(requested.get()).doesNotExist();
    }

    @Test
    void savedImage_runsTheReaderWhileTheFileExists_thenDeletesIt() throws IOException {
        OBSRemoteController obs = obsWithScene("Dota");
        AtomicReference<Path> requested = answerScreenshot(obs, response(true), new byte[] {1, 2, 3});

        ProgramScene.Shot<Long> shot =
                ProgramScene.screenshot(obs, PREFIX, "png", 64, 36, TIMEOUT_MS, Files::size);

        assertThat(shot.outcome()).isEqualTo(ProgramScene.Outcome.SAVED);
        assertThat(shot.value()).as("the reader saw the bytes OBS wrote").isEqualTo(3L);
        assertThat(requested.get()).doesNotExist();
    }

    @Test
    void readerFailure_propagates_andStillDeletesTheTempFile() {
        OBSRemoteController obs = obsWithScene("Dota");
        AtomicReference<Path> requested = answerScreenshot(obs, response(true), new byte[] {1});

        assertThatThrownBy(
                        () ->
                                ProgramScene.screenshot(
                                        obs, PREFIX, "png", 64, 36, TIMEOUT_MS,
                                        image -> {
                                            throw new IOException("undecodable");
                                        }))
                .isInstanceOf(IOException.class);
        assertThat(requested.get()).doesNotExist();
    }

    @Test
    void passesTheCallersTimeoutSizeFormatAndPrefixToObs() throws IOException {
        OBSRemoteController obs = obsWithScene("Dota");
        AtomicReference<Path> requested = answerScreenshot(obs, response(true), new byte[] {1});

        ProgramScene.screenshot(obs, PREFIX, "png", 64, 36, TIMEOUT_MS);

        verify(obs).getCurrentProgramScene(TIMEOUT_MS);
        verify(obs)
                .saveSourceScreenshot(
                        eq("Dota"), eq("png"), anyString(), eq(64), eq(36), eq(-1), eq(TIMEOUT_MS));
        assertThat(requested.get().getFileName().toString()).startsWith(PREFIX).endsWith(".png");
    }
}
