package dev.dotarec.bridge;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Shared HTTP Range streaming for the recorded VODs and the derived clip .mp4s. Both the
 * {@link MatchController} match stream and the {@link ClipController} clip stream serve a file on disk
 * to a renderer {@code <video>} element over the authed loopback bridge (no cross-origin
 * {@code file://} load) with the same semantics: a {@code Range} header yields 206 Partial Content
 * with a {@code Content-Range} (Chromium's seek path), an absent header yields 200 with
 * {@code Accept-Ranges: bytes} and the full body, and an unsatisfiable range yields 416. The body is
 * streamed in 64KB chunks via {@link StreamingResponseBody} (no whole-file buffering).
 *
 * <p><b>Per-response window.</b> A {@code <video>} seek sends an <em>open-ended</em>
 * {@code Range: bytes=N-}. Answering that literally would stream from {@code N} to EOF — the entire
 * multi-GB remainder of a VOD — in one response. Chromium reads only enough to fill its forward
 * buffer, then stops reading while holding the connection open (HTTP flow control), so the writer
 * thread blocks in {@code out.write()} with the socket send buffer full. Rapid seeks (arrow-key
 * scrubbing) pile these stalled responses up against Chromium's ~6-connections-per-origin limit; the
 * next seek can't get a connection and playback freezes until the stalled writes hit Tomcat's async
 * timeout. So an open-ended (or oversized) range is clamped to {@link #MAX_STREAM_CHUNK} bytes: the
 * response completes promptly (fits in socket buffers, frees the thread/connection), and the browser
 * fetches the next window when its buffer drains. An explicit bounded range already within the window
 * is served exactly (the clamp is a no-op).
 */
final class VideoStreamSupport {

    /**
     * Maximum bytes served per 206 response. Bounds how long a writer thread is held (and thus how
     * many connections a burst of seeks can tie up) while keeping the request count over the loopback
     * bridge sane. Chromium re-requests the next window as its buffer drains.
     */
    static final long MAX_STREAM_CHUNK = 4L * 1024 * 1024;

    private VideoStreamSupport() {
    }

    /**
     * Path-traversal guard for the file-serving stream endpoints: a stored {@code video_path}/
     * {@code thumb_path} must resolve to a real file that lives <em>under one of the configured storage
     * roots</em> ({@code settings.videoDir} plus each {@code settings.storageLocations[].path}). A path
     * outside every root (a tampered DB row, a {@code ..} escape) is rejected — the caller maps that to
     * a 404. Archived VODs/clips live under an archive root, so <em>all</em> roots are allowed: a
     * legitimately archived file is never rejected.
     *
     * <p>Mirrors {@code RecordingArchiver.locationOf} normalization: each candidate is reduced to an
     * absolute, normalized path and matched as a case-insensitive string prefix terminated by a file
     * separator (Windows paths are case-insensitive, and the trailing separator keeps {@code C:\vid}
     * from matching a sibling {@code C:\video2\...}).
     *
     * @param file  the resolved file to serve
     * @param roots the configured storage roots (any blank/unparseable root is skipped)
     * @return true when {@code file} is contained by at least one root
     */
    static boolean isUnderAnyRoot(Path file, List<String> roots) {
        String fileStr;
        try {
            fileStr = file.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return false;
        }
        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            String dirStr;
            try {
                dirStr = Path.of(root).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
            } catch (RuntimeException e) {
                continue;
            }
            String dirPrefix = dirStr.endsWith(File.separator) ? dirStr : dirStr + File.separator;
            if (fileStr.startsWith(dirPrefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the ranged streaming response for {@code file}, honoring the request {@code Range}
     * header. The caller is responsible for verifying the file exists (a 404 belongs to the resource
     * controller, not here).
     */
    static ResponseEntity<StreamingResponseBody> stream(Path file, HttpHeaders headers) {
        return stream(file, headers, MAX_STREAM_CHUNK);
    }

    /**
     * Range streaming with an explicit per-response window (bytes). Exposed for tests to exercise the
     * open-ended clamp without a multi-megabyte fixture; production callers use the {@code MAX_STREAM_CHUNK}
     * default via {@link #stream(Path, HttpHeaders)}.
     */
    static ResponseEntity<StreamingResponseBody> stream(Path file, HttpHeaders headers, long maxChunk) {
        MediaType type = contentType(file);
        long length;
        try {
            length = Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read video size: " + file, e);
        }

        // Stream the bytes via StreamingResponseBody rather than a (Generic)HttpMessageConverter:
        // it streams in chunks (no whole-file buffering) and is written by a dedicated return-value
        // handler, so it sidesteps the converter-generics trap that erases ResourceRegion under a
        // ResponseEntity<?> return type. <video> always sends a Range, so the 206 path is the hot one.
        // A malformed Range header (e.g. "bytes=abc") makes getRange() throw IllegalArgumentException;
        // with no @ControllerAdvice that would surface as a 500. Lenient clients ignore a bad Range, so
        // treat it as absent and serve the full 200 body rather than failing the request.
        List<HttpRange> ranges;
        try {
            ranges = headers.getRange();
        } catch (IllegalArgumentException e) {
            ranges = List.of();
        }
        if (ranges.isEmpty()) {
            // No Range header -> full body, 200. Advertise Accept-Ranges so Chromium knows it can seek.
            return ResponseEntity.ok()
                    .contentType(type)
                    .contentLength(length)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(out -> copyRange(file, 0, length, out));
        }
        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(length);
        long end = range.getRangeEnd(length); // clamped to length-1 by HttpRange
        if (start >= length || start > end) {
            // Unsatisfiable (start beyond EOF): 416 with the actual size so the client can re-ask.
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + length)
                    .build();
        }
        // Cap the served slice to one window so an open-ended seek range (bytes=N-, i.e. end=length-1)
        // doesn't stream the whole multi-GB remainder in a single stalling response. A bounded range
        // already inside the window is unaffected (min is a no-op); the browser re-requests the next
        // window as its buffer drains. Content-Range still reports the ACTUAL served slice, so the
        // client knows more remains and keeps seeking forward.
        end = Math.min(end, start + maxChunk - 1);
        long regionLen = end - start + 1;
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(type)
                .contentLength(regionLen)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length)
                .body(out -> copyRange(file, start, regionLen, out));
    }

    /** Streams {@code count} bytes of {@code file} starting at {@code start} to {@code out}, chunked. */
    private static void copyRange(Path file, long start, long count, OutputStream out)
            throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            in.skipNBytes(start);
            byte[] buf = new byte[64 * 1024];
            long remaining = count;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) {
                    break;
                }
                out.write(buf, 0, n);
                remaining -= n;
            }
        }
    }

    /**
     * Explicit extension -> media type map. {@code Files.probeContentType} is unreliable on Windows
     * (depends on registry MIME registrations), so the recording containers are mapped by hand.
     */
    private static MediaType contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp4")) {
            return MediaType.valueOf("video/mp4");
        }
        if (name.endsWith(".mkv")) {
            return MediaType.valueOf("video/x-matroska");
        }
        if (name.endsWith(".mov")) {
            return MediaType.valueOf("video/quicktime");
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
