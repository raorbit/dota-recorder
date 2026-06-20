package dev.dotarec.obs.setup;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Picks the recording encoder for the auto-generated OBS profile from the installed GPU.
 *
 * <p>The recorder configures OBS with no user input, so it must choose a sensible encoder itself:
 * a hardware encoder when one exists (so recording does not steal CPU from Dota), with the
 * universal software x264 as the always-available fallback. The choice is a vendor heuristic over
 * the GPU name(s) reported by {@code Win32_VideoController} -- not a true capability probe (which
 * would require launching OBS), but enough to pick the right family; if the chosen HW encoder is
 * unavailable at OBS startup, OBS itself falls back, and PR5 validates against the live encoder.
 *
 * <p>The GPU-name source is injectable so the mapping is unit-tested without touching WMI.
 */
@Component
public class EncoderProbe {

    private static final Logger log = LoggerFactory.getLogger(EncoderProbe.class);

    // OBS Simple-output {@code RecEncoder} SELECTOR TOKENS, not the real encoder ids. OBS's
    // get_simple_output_encoder() strcmp-matches these short tokens and resolves each to the actual
    // encoder (nvenc -> jim_nvenc, qsv -> obs_qsv11_v2, amd -> h264_texture_amf); ANY unrecognized
    // value silently falls back to software obs_x264, so the advanced ids must NOT be used here.
    static final String X264 = "x264";
    static final String NVENC = "nvenc";
    static final String AMF = "amd";
    static final String QSV = "qsv";

    private final Supplier<List<String>> gpuNames;

    public EncoderProbe() {
        this(EncoderProbe::queryGpuNamesViaWmi);
    }

    /** Test seam: supply fake GPU names instead of querying WMI. */
    EncoderProbe(Supplier<List<String>> gpuNames) {
        this.gpuNames = gpuNames;
    }

    /**
     * Returns the OBS Simple-output {@code RecEncoder} token for the best available encoder: NVENC
     * (NVIDIA) &gt; AMF (AMD) &gt; QSV (Intel) &gt; x264. Any failure to enumerate GPUs degrades to x264.
     */
    public String detect() {
        List<String> names;
        try {
            names = gpuNames.get();
        } catch (RuntimeException e) {
            log.warn("GPU enumeration failed ({}); recording with x264.", e.toString());
            return X264;
        }
        if (names == null || names.isEmpty()) {
            return X264;
        }
        String joined =
                names.stream()
                        .filter(Objects::nonNull)
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .collect(Collectors.joining(" | "));
        // Discrete GPUs first; an Intel iGPU's QSV is the last hardware choice before x264.
        if (joined.contains("nvidia")) {
            return NVENC;
        }
        if (joined.contains("amd") || joined.contains("radeon")) {
            return AMF;
        }
        if (joined.contains("intel")) {
            return QSV;
        }
        return X264;
    }

    /** Enumerates display adapter names via PowerShell/CIM, bounded by a hard timeout. */
    private static List<String> queryGpuNamesViaWmi() {
        Process p = null;
        try {
            p =
                    new ProcessBuilder(
                                    "powershell",
                                    "-NoProfile",
                                    "-NonInteractive",
                                    "-Command",
                                    "Get-CimInstance Win32_VideoController | ForEach-Object { $_.Name }")
                            .redirectErrorStream(true)
                            .start();
            // Wait first (output is tiny, so the pipe buffer cannot fill and deadlock the child);
            // a hung PowerShell is force-killed rather than blocking core startup.
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return List.of();
            }
            List<String> names = new ArrayList<>();
            try (BufferedReader r =
                    new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.trim();
                    if (!t.isEmpty()) {
                        names.add(t);
                    }
                }
            }
            return names;
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
}
