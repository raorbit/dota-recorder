package dev.dotarec.obs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dotarec.bridge.EventPublisher;
import io.obswebsocket.community.client.OBSRemoteController;
import io.obswebsocket.community.client.message.response.scenes.GetCurrentProgramSceneResponse;
import io.obswebsocket.community.client.message.response.sources.SaveSourceScreenshotResponse;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CaptureBlackFrameGuard}: the pure luminance measurement, the black/non-black
 * decision in {@link CaptureBlackFrameGuard#runCheck()} driven through an overridden sampler so no
 * live OBS is needed, and the real sampler against a mocked OBS.
 */
class CaptureBlackFrameGuardTest {

    /** Subclass returning a canned luminance so runCheck's decision is testable without OBS. */
    private static final class FakeGuard extends CaptureBlackFrameGuard {
        private final Double cannedLuma;

        FakeGuard(ObsHealth health, EventPublisher events, Double cannedLuma) {
            super(mock(ObsController.class), health, events);
            this.cannedLuma = cannedLuma;
        }

        @Override
        Double sampleProgramSceneLuma() {
            return cannedLuma;
        }
    }

    private static BufferedImage solid(Color c) {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setRGB(x, y, c.getRGB());
            }
        }
        return img;
    }

    @Test
    void meanLuma_blackIsZero() {
        assertThat(CaptureBlackFrameGuard.meanLuma(solid(Color.BLACK))).isEqualTo(0.0);
    }

    @Test
    void meanLuma_whiteIsMax() {
        assertThat(CaptureBlackFrameGuard.meanLuma(solid(Color.WHITE))).isCloseTo(255.0, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void meanLuma_midGrayIsAboutHalf() {
        assertThat(CaptureBlackFrameGuard.meanLuma(solid(new Color(128, 128, 128))))
                .isCloseTo(128.0, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void meanLuma_aBrightHudPixelLiftsAnOtherwiseDarkFrameAboveThreshold() {
        // A near-black night-time scene with even a few bright HUD pixels must not read as "black" —
        // this is why the threshold is so low. One bright row over an 8x8 frame already clears it.
        BufferedImage img = solid(Color.BLACK);
        for (int x = 0; x < img.getWidth(); x++) {
            img.setRGB(x, 0, Color.WHITE.getRGB());
        }
        assertThat(CaptureBlackFrameGuard.meanLuma(img))
                .isGreaterThan(CaptureBlackFrameGuard.BLACK_LUMA_THRESHOLD);
    }

    @Test
    void runCheck_flagsAndPublishesWhenBlackWhileRecording() {
        ObsHealth health = new ObsHealth();
        health.setRecording(true);
        EventPublisher events = mock(EventPublisher.class);
        FakeGuard guard = new FakeGuard(health, events, 0.4); // below threshold

        guard.runCheck();

        assertThat(health.isCaptureBlack()).isTrue();
        verify(events).publishStatus();
    }

    @Test
    void runCheck_clearsFlagWhenBright() {
        ObsHealth health = new ObsHealth();
        health.setRecording(true);
        health.setCaptureBlack(true); // stale from a prior sample
        EventPublisher events = mock(EventPublisher.class);
        FakeGuard guard = new FakeGuard(health, events, 120.0);

        guard.runCheck();

        assertThat(health.isCaptureBlack()).isFalse();
    }

    @Test
    void runCheck_skipsWhenNotRecording() {
        ObsHealth health = new ObsHealth();
        health.setRecording(false); // recording ended within the delay window
        EventPublisher events = mock(EventPublisher.class);
        FakeGuard guard = new FakeGuard(health, events, 0.0); // would be black, but we're not recording

        guard.runCheck();

        assertThat(health.isCaptureBlack()).isFalse();
        verify(events, never()).publishStatus();
    }

    /**
     * A guard whose REAL sampler runs against a mocked OBS, whose SaveSourceScreenshot answers
     * {@code successful} after writing {@code frame} (when non-null) as a PNG where it was asked to.
     * Records the temp path OBS was handed in {@code requested}.
     */
    private static CaptureBlackFrameGuard guardShooting(
            String sceneName, boolean successful, BufferedImage frame, AtomicReference<Path> requested) {
        OBSRemoteController c = mock(OBSRemoteController.class);
        GetCurrentProgramSceneResponse scene = mock(GetCurrentProgramSceneResponse.class);
        when(scene.isSuccessful()).thenReturn(true);
        when(scene.getCurrentProgramSceneName()).thenReturn(sceneName);
        when(c.getCurrentProgramScene(anyLong())).thenReturn(scene);
        SaveSourceScreenshotResponse resp = mock(SaveSourceScreenshotResponse.class);
        when(resp.isSuccessful()).thenReturn(successful);
        when(c.saveSourceScreenshot(anyString(), anyString(), anyString(), any(), any(), any(), anyLong()))
                .thenAnswer(
                        inv -> {
                            Path p = Path.of(inv.getArgument(2, String.class));
                            requested.set(p);
                            if (frame != null) {
                                ImageIO.write(frame, "png", p.toFile());
                            }
                            return resp;
                        });
        ObsController obs = mock(ObsController.class);
        when(obs.connectedController()).thenReturn(c);
        return new CaptureBlackFrameGuard(obs, new ObsHealth(), mock(EventPublisher.class));
    }

    @Test
    void sampleProgramSceneLuma_measuresTheSavedSceneFrame_andCleansUpItsTempFile() {
        AtomicReference<Path> requested = new AtomicReference<>();
        CaptureBlackFrameGuard guard = guardShooting("Dota", true, solid(Color.BLACK), requested);

        assertThat(guard.sampleProgramSceneLuma()).isEqualTo(0.0);
        assertThat(requested.get().getFileName().toString()).startsWith("dotarec-blackcheck-");
        assertThat(requested.get()).doesNotExist();
    }

    @Test
    void sampleProgramSceneLuma_isUnknownWhenTheScreenshotIsRefused() {
        // A refusal is the render probe's signal, not black: the guard must not raise a false alarm.
        AtomicReference<Path> requested = new AtomicReference<>();
        CaptureBlackFrameGuard guard = guardShooting("Dota", false, null, requested);

        assertThat(guard.sampleProgramSceneLuma()).isNull();
        assertThat(requested.get()).doesNotExist();
    }

    @Test
    void sampleProgramSceneLuma_isUnknownWithNoActiveScene() {
        AtomicReference<Path> requested = new AtomicReference<>();
        CaptureBlackFrameGuard guard = guardShooting("", true, solid(Color.BLACK), requested);

        assertThat(guard.sampleProgramSceneLuma()).isNull();
        assertThat(requested.get()).as("no scene, so nothing was shot").isNull();
    }

    @Test
    void runCheck_doesNotFlagWhenSampleUnavailable() {
        // A failed screenshot (null) is "unknown", never "black": no false alarm.
        ObsHealth health = new ObsHealth();
        health.setRecording(true);
        EventPublisher events = mock(EventPublisher.class);
        FakeGuard guard = new FakeGuard(health, events, null);

        guard.runCheck();

        assertThat(health.isCaptureBlack()).isFalse();
        verify(events, never()).publishStatus();
    }
}
