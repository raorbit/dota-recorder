package dev.dotarec.obs.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EncoderProbeTest {

    private static String detectFrom(List<String> gpuNames) {
        return new EncoderProbe(() -> gpuNames).detect();
    }

    @Test
    void nvidiaPicksNvenc() {
        assertThat(detectFrom(List.of("NVIDIA GeForce RTX 4070"))).isEqualTo("jim_nvenc");
    }

    @Test
    void amdPicksAmf() {
        assertThat(detectFrom(List.of("AMD Radeon RX 7800 XT"))).isEqualTo("h264_texture_amf");
        assertThat(detectFrom(List.of("Radeon RX 580 Series"))).isEqualTo("h264_texture_amf");
    }

    @Test
    void intelPicksQsv() {
        assertThat(detectFrom(List.of("Intel(R) UHD Graphics 770"))).isEqualTo("obs_qsv11");
    }

    @Test
    void unknownOrEmptyFallsBackToX264() {
        assertThat(detectFrom(List.of("Microsoft Basic Display Adapter"))).isEqualTo("x264");
        assertThat(detectFrom(List.of())).isEqualTo("x264");
    }

    @Test
    void discreteNvidiaWinsOverIntelIgpu() {
        assertThat(detectFrom(List.of("Intel(R) UHD Graphics", "NVIDIA GeForce RTX 4090")))
                .isEqualTo("jim_nvenc");
    }

    @Test
    void enumerationFailureFallsBackToX264() {
        EncoderProbe probe =
                new EncoderProbe(
                        () -> {
                            throw new RuntimeException("WMI unavailable");
                        });
        assertThat(probe.detect()).isEqualTo("x264");
    }
}
