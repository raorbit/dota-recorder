package dev.dotarec.clip;

import dev.dotarec.config.FfmpegLocator;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Renders a sub-range of a recorded VOD into its own .mp4 by invoking the bundled ffmpeg.
 *
 * <p>The fast path is a stream copy ({@code -c copy}) seeking before input ({@code -ss} before
 * {@code -i}) with the duration relative to the seek point ({@code -t}, not {@code -to}). A copy cut
 * lands on the nearest keyframe, which is sufficient for a clip and avoids a re-encode. If the copy
 * fails — non-zero exit, or a missing/empty output — we retry once with an x264/AAC re-encode, which
 * is slower but frame-accurate and tolerant of containers a raw copy chokes on.
 */
@Component
public class Clipper {

    private static final Logger log = LoggerFactory.getLogger(Clipper.class);

    /**
     * Floor wall-clock cap for any single ffmpeg run, so a hung process can never pin a pool thread
     * (which also holds the StorageMaintenanceLock) forever. Enough for a stream-copy cut or a
     * thumbnail of any length.
     */
    private static final long MIN_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);

    /**
     * Wall-clock budget per second of requested clip for the re-encode fallback. A long MANUAL clip
     * that can't stream-copy falls back to an x264 veryfast re-encode whose cost scales with length, so
     * a fixed cap would kill a legitimate long clip mid-encode. 3x realtime is comfortably above
     * veryfast even on weak hardware while still bounding a genuinely hung process.
     */
    private static final long REENCODE_MS_PER_SECOND = 3_000L;

    /**
     * Absolute ceiling regardless of requested length, so a pathological request can't pin a pool
     * thread forever. Deliberately modest (supports up to a ~10-min clip re-encoded at 3x realtime):
     * {@code ClipQueue.STALE_GENERATING_MS} must strictly exceed 3x this so a legitimately long render
     * is never reclaimed as stale and double-cut — the two constants are co-designed.
     */
    private static final long MAX_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);

    private final FfmpegLocator ffmpeg;

    public Clipper(FfmpegLocator ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    /**
     * Cuts {@code [startSeconds, startSeconds + durationSeconds)} out of {@code source} into
     * {@code output}, returning the rendered file's path and size.
     *
     * @param source          the parent VOD to cut from
     * @param startSeconds    seek point, in seconds from the start of the source
     * @param durationSeconds clip length in seconds (end − start)
     * @param output          destination .mp4 (its parent directory must already exist)
     * @return the {@link Result} (output path + byte size)
     * @throws IllegalStateException if ffmpeg is unavailable or both the copy and re-encode attempts fail
     */
    public Result clip(Path source, double startSeconds, double durationSeconds, Path output) {
        // Use the resolved bundled/configured ffmpeg, else the bare "ffmpeg" on PATH (command()'s
        // fallback). We deliberately do NOT gate on isAvailable() — that returns false whenever nothing
        // is bundled/configured, which would defeat the PATH fallback for dev/standalone runs that have
        // ffmpeg on PATH. A genuinely missing binary surfaces as a failed run() (IOException) and the
        // normal "ffmpeg failed" error below.
        String ffmpegCmd = ffmpeg.command();
        String start = formatSeconds(Math.max(0.0, startSeconds));
        String duration = formatSeconds(Math.max(0.0, durationSeconds));
        // Scale the per-run cap to the requested length so the re-encode fallback of a long clip isn't
        // killed mid-encode (see REENCODE_MS_PER_SECOND); a short clip still gets the MIN_TIMEOUT_MS floor.
        long timeoutMs = clipTimeoutMs(durationSeconds);

        // Fast path: stream copy.
        Attempt copy = run(buildCopyCommand(ffmpegCmd, source, start, duration, output), output, timeoutMs);
        if (copy.succeeded()) {
            return new Result(output, copy.size());
        }
        log.warn("Clip stream-copy failed (exit={}), retrying with re-encode: {} -> {}",
                copy.exitCode(), source, output);

        // Retry once with a re-encode.
        Attempt reencode =
                run(buildReencodeCommand(ffmpegCmd, source, start, duration, output), output, timeoutMs);
        if (reencode.succeeded()) {
            return new Result(output, reencode.size());
        }
        boolean timedOut = copy.timedOut() || reencode.timedOut();
        throw new IllegalStateException(
                "ffmpeg failed to generate clip " + output + " (copy exit=" + copy.exitCode()
                        + ", re-encode exit=" + reencode.exitCode()
                        + (timedOut ? ", timed out after " + (timeoutMs / 60_000) + " min" : "")
                        + "). Last output:\n" + reencode.tail());
    }

    /**
     * Grabs a single JPEG frame from {@code source} at {@code atSeconds} into {@code output} via
     * ffmpeg ({@code -ss <at>} before {@code -i}, then {@code -frames:v 1}). Used to render a clip's
     * thumbnail. {@code output}'s parent directory must already exist.
     *
     * @param source    the video to grab a frame from
     * @param atSeconds seek point, in seconds from the start of the source
     * @param output    destination .jpg
     * @return the {@code output} path
     * @throws IllegalStateException if ffmpeg is unavailable or fails to produce a non-empty frame
     */
    public Path thumbnail(Path source, double atSeconds, Path output) {
        // See clip(): use command() (bundled/configured, else bare "ffmpeg" on PATH), not isAvailable().
        String at = formatSeconds(Math.max(0.0, atSeconds));
        Attempt grab = run(buildThumbnailCommand(ffmpeg.command(), source, at, output), output, MIN_TIMEOUT_MS);
        if (grab.succeeded()) {
            return output;
        }
        throw new IllegalStateException(
                "ffmpeg failed to generate thumbnail " + output + " (exit=" + grab.exitCode()
                        + "). Last output:\n" + grab.tail());
    }

    private List<String> buildThumbnailCommand(String ffmpegCmd, Path source, String at,
                                               Path output) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegCmd);
        cmd.add("-y");
        cmd.add("-ss");
        cmd.add(at);
        cmd.add("-i");
        cmd.add(source.toString());
        cmd.add("-frames:v");
        cmd.add("1");
        cmd.add("-q:v");
        cmd.add("3");
        cmd.add(output.toString());
        return cmd;
    }

    private List<String> buildCopyCommand(String ffmpegCmd, Path source, String start,
                                          String duration, Path output) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegCmd);
        cmd.add("-y");
        cmd.add("-ss");
        cmd.add(start);
        cmd.add("-i");
        cmd.add(source.toString());
        cmd.add("-t");
        cmd.add(duration);
        cmd.add("-c");
        cmd.add("copy");
        cmd.add(output.toString());
        return cmd;
    }

    private List<String> buildReencodeCommand(String ffmpegCmd, Path source, String start,
                                              String duration, Path output) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegCmd);
        cmd.add("-y");
        cmd.add("-ss");
        cmd.add(start);
        cmd.add("-i");
        cmd.add(source.toString());
        cmd.add("-t");
        cmd.add(duration);
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("veryfast");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add(output.toString());
        return cmd;
    }

    /**
     * Runs ffmpeg, capturing its combined stdout/stderr, and reports whether the cut produced a
     * non-empty output. A non-zero exit, a timeout, an interruption, or a missing/zero-byte output all
     * count as failure.
     */
    private Attempt run(List<String> command, Path output, long timeoutMs) {
        log.debug("Running ffmpeg: {}", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            log.warn("Failed to run ffmpeg for {}: {}", output, e.getMessage());
            return new Attempt(-1, 0L, e.getMessage() == null ? "" : e.getMessage(), false);
        }
        // Drain the merged stdout/stderr on a SEPARATE thread. Reading it inline before waitFor() would
        // block forever on a hung ffmpeg that stops emitting output but never exits (its pipe never
        // closes), so the timeout below could never fire — pinning this pool thread while it holds the
        // StorageMaintenanceLock. The drain reader unblocks when the stream closes (process exit or the
        // destroyForcibly() on timeout).
        StringBuilder out = new StringBuilder();
        Thread drainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (out) {
                        out.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // Stream closed by process death / forcible destroy — nothing more to capture.
            }
        }, "clip-ffmpeg-drain");
        drainer.setDaemon(true);
        drainer.start();
        try {
            boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                drainer.join(TimeUnit.SECONDS.toMillis(5));
                log.warn("ffmpeg timed out after {} min, killed: {}", timeoutMs / 60_000, output);
                synchronized (out) {
                    return new Attempt(-1, 0L, out.toString(), true);
                }
            }
            drainer.join(TimeUnit.SECONDS.toMillis(5));
            int exit = proc.exitValue();
            long size = outputSize(output);
            boolean ok = exit == 0 && size > 0L;
            synchronized (out) {
                return new Attempt(ok ? 0 : (exit == 0 ? -1 : exit), size, out.toString(), false);
            }
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            try {
                drainer.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException joinInterrupted) {
                // Re-interrupted while waiting for the drainer; stop waiting and fall through —
                // the interrupt flag is reasserted below.
            }
            Thread.currentThread().interrupt();
            log.warn("Interrupted while running ffmpeg for {}", output);
            return new Attempt(-1, 0L, "interrupted", false);
        }
    }

    private static long outputSize(Path output) {
        try {
            return Files.isRegularFile(output) ? Files.size(output) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    /** ffmpeg accepts plain seconds; a fixed 3-decimal, dot-separated value avoids locale commas. */
    private static String formatSeconds(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }

    /**
     * Per-run wall-clock cap for a clip of {@code durationSeconds}: the {@link #MIN_TIMEOUT_MS} floor,
     * or {@link #REENCODE_MS_PER_SECOND} per requested second when longer, capped at
     * {@link #MAX_TIMEOUT_MS} so a pathological request can't pin a pool thread indefinitely.
     */
    private static long clipTimeoutMs(double durationSeconds) {
        long scaled = (long) Math.ceil(Math.max(0.0, durationSeconds) * REENCODE_MS_PER_SECOND);
        return Math.min(MAX_TIMEOUT_MS, Math.max(MIN_TIMEOUT_MS, scaled));
    }

    /** The path and byte size of a successfully rendered clip. */
    public record Result(Path output, long sizeBytes) {
    }

    /** Outcome of one ffmpeg invocation. {@code exitCode == 0 && size > 0} means success. */
    private record Attempt(int exitCode, long size, String capturedOutput, boolean timedOut) {
        boolean succeeded() {
            return exitCode == 0 && size > 0L;
        }

        /** Last few lines of captured ffmpeg output, for an error message. */
        String tail() {
            if (capturedOutput == null || capturedOutput.isBlank()) {
                return "";
            }
            String[] lines = capturedOutput.split("\n");
            int from = Math.max(0, lines.length - 8);
            return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
        }
    }
}
