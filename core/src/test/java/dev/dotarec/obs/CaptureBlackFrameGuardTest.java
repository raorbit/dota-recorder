package dev.dotarec.obs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.dotarec.bridge.EventPublisher;
import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CaptureBlackFrameGuard}: the pure luminance measurement, and the
 * black/non-black decision in {@link CaptureBlackFrameGuard#runCheck()} driven through an overridden
 * sampler so no live OBS is needed.
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
