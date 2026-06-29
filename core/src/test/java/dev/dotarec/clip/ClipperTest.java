package dev.dotarec.clip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.dotarec.config.FfmpegLocator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link Clipper} without a brittle ProcessBuilder mock.
 *
 * <p>Two strata: (1) the failure path that needs no real ffmpeg -- a {@link FfmpegLocator} pointed at
 * a regular file that is not a runnable executable makes every ffmpeg invocation fail at {@code
 * ProcessBuilder.start()} (IOException), so {@code clip()}/{@code thumbnail()} surface an {@link
 * IllegalStateException} promptly rather than hanging; and (2) one real integration test that cuts a
 * tiny lavfi-generated input and grabs a frame, guarded with {@link org.junit.jupiter.api.Assumptions}
 * so it auto-skips wherever ffmpeg is absent (CI, machines without the bundled binary). The
 * integration test also implicitly exercises the locale-safe, dot-decimal {@code formatSeconds}
 * (a real ffmpeg would reject a comma-formatted seek/duration argument).
 */
class ClipperTest {

    @Test
    void clip_whenFfmpegCommandIsNotRunnable_throwsIllegalStateExceptionWithoutHanging(
            @TempDir Path dir) throws IOException {
        Clipper clipper = new Clipper(bogusFfmpeg(dir));
        Path source = Files.writeString(dir.resolve("source.mp4"), "not really a video");
        Path output = dir.resolve("clip.mp4");

        // command() returns the bogus path, ProcessBuilder.start() throws IOException for both the
        // copy and re-encode attempts, so both fail fast and clip() raises -- it must not block.
        assertThatThrownBy(() -> clipper.clip(source, 1.0, 2.0, output))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ffmpeg failed to generate clip");

        assertThat(Files.exists(output)).isFalse();
    }

    @Test
    void thumbnail_whenFfmpegCommandIsNotRunnable_throwsIllegalStateExceptionWithoutHanging(
            @TempDir Path dir) throws IOException {
        Clipper clipper = new Clipper(bogusFfmpeg(dir));
        Path source = Files.writeString(dir.resolve("source.mp4"), "not really a video");
        Path output = dir.resolve("thumb.jpg");

        assertThatThrownBy(() -> clipper.thumbnail(source, 1.0, output))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ffmpeg failed to generate thumbnail");

        assertThat(Files.exists(output)).isFalse();
    }

    @Test
    void clipAndThumbnail_withRealFfmpeg_renderNonEmptyOutputs(@TempDir Path dir) throws IOException {
        Path ffmpegExe = locateFfmpeg();
        assumeTrue(ffmpegExe != null, "ffmpeg not available on PATH or bundled; skipping");

        Clipper clipper = new Clipper(new FfmpegLocator(ffmpegExe.toString()));

        // Generate a tiny 3s test clip with ffmpeg's lavfi (color video + sine audio) so the cut has a
        // real container/codecs to work on -- no fixture file checked into the repo, no network.
        Path source = dir.resolve("source.mp4");
        generateTestInput(ffmpegExe, source);
        assumeTrue(Files.isRegularFile(source) && fileSize(source) > 0L,
                "could not generate ffmpeg test input; skipping");

        Path clip = dir.resolve("clip.mp4");
        Clipper.Result result = clipper.clip(source, 0.5, 1.0, clip);

        assertThat(result.output()).isEqualTo(clip);
        assertThat(result.sizeBytes()).isPositive();
        assertThat(Files.isRegularFile(clip)).isTrue();
        assertThat(fileSize(clip)).isEqualTo(result.sizeBytes());

        Path thumb = dir.resolve("thumb.jpg");
        Path thumbOut = clipper.thumbnail(source, 0.5, thumb);

        assertThat(thumbOut).isEqualTo(thumb);
        assertThat(Files.isRegularFile(thumb)).isTrue();
        assertThat(fileSize(thumb)).isPositive();
    }

    /**
     * A {@link FfmpegLocator} whose configured path is a real regular file that is NOT a runnable
     * executable, so {@code command()} returns it and {@code ProcessBuilder.start()} fails with an
     * IOException (Windows CreateProcess error 193) on every attempt -- deterministic, no PATH
     * dependency, and never hangs.
     */
    private static FfmpegLocator bogusFfmpeg(Path dir) throws IOException {
        Path notAnExe = Files.writeString(dir.resolve("not-ffmpeg.txt"), "this is not an executable");
        return new FfmpegLocator(notAnExe.toAbsolutePath().toString());
    }

    /**
     * Resolve a usable ffmpeg for the integration test: the bundled
     * {@code build-resources/ffmpeg/ffmpeg.exe} if present, else a bare {@code ffmpeg} on PATH.
     * Returns {@code null} when neither is found so the caller can {@code assumeTrue}-skip.
     */
    private static Path locateFfmpeg() {
        Path bundled = repoRoot().resolve("build-resources").resolve("ffmpeg").resolve("ffmpeg.exe");
        if (Files.isRegularFile(bundled)) {
            return bundled;
        }
        return onPath("ffmpeg").or(() -> onPath("ffmpeg.exe")).orElse(null);
    }

    /** Repo root from the test's working directory (Gradle runs core tests with cwd = {@code core/}). */
    private static Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        return cwd.getFileName() != null && cwd.getFileName().toString().equals("core")
                ? cwd.getParent()
                : cwd;
    }

    /** Probe the OS {@code PATH} for an executable of the given name. */
    private static Optional<Path> onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, exe);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Render a 3-second test clip (testsrc video + sine audio) so the cut has real codecs to copy. */
    private static void generateTestInput(Path ffmpegExe, Path output) throws IOException {
        java.util.List<String> cmd = java.util.List.of(
                ffmpegExe.toString(), "-y",
                "-f", "lavfi", "-i", "testsrc=duration=3:size=160x120:rate=15",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest",
                output.toString());
        runQuietly(cmd);
    }

    private static void runQuietly(java.util.List<String> cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process proc = pb.start();
        try {
            proc.waitFor();
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static long fileSize(Path p) throws IOException {
        return Files.size(p);
    }
}
