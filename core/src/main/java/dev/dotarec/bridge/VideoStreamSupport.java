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
 */
final class VideoStreamSupport {

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
        List<HttpRange> ranges = headers.getRange();
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
