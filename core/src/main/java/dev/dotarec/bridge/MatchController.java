package dev.dotarec.bridge;

import dev.dotarec.data.MarkerRepository;
import dev.dotarec.data.MarkerRow;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchSummary;
import dev.dotarec.data.PauseRepository;
import dev.dotarec.data.PauseSpan;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
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
 * Matches endpoints consumed by the Electron browse/player UI over the loopback bridge.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@code GET /matches?bucket=&result=&q=&from=&to=} -- filtered, newest-first list. With no
 *       params it returns the full list (back-compat with v0.1). Filters are exact per
 *       {@link MatchRepository#findMatches}.</li>
 *   <li>{@code GET /matches/{id}} -- one match, or 404.</li>
 *   <li>{@code GET /matches/{id}/markers} -- the seekable timeline, ordered by video offset.</li>
 *   <li>{@code GET /matches/{id}/pauses} -- pause spans, chronological.</li>
 *   <li>{@code GET /matches/{id}/video} -- a {@code file://} URL + absolute path to the .mp4, or 404
 *       when the row is missing or its video was pruned ({@code video_path} null). Retained for
 *       back-compat; the player now streams the bytes via {@code /video/stream} (below).</li>
 *   <li>{@code GET /matches/{id}/video/stream} -- the recorded VOD bytes over the authed loopback
 *       bridge so a renderer {@code <video>} element can play + seek without a cross-origin
 *       {@code file://} load. Honors HTTP {@code Range}: a {@code Range} header yields 206 Partial
 *       Content with a {@code Content-Range} (Chromium's seek path), an absent header yields 200
 *       with {@code Accept-Ranges: bytes} and the full body, an unsatisfiable range yields 416.
 *       404 with the same reasons as {@code /video} (row missing / {@code video_path} null / file
 *       gone from disk). A {@code <video src>} can't set the {@code X-Dotarec-Token} header, so the
 *       renderer passes the bridge token on the {@code ?token=} query param -- {@code BridgeAuthFilter}
 *       already accepts the token there for every gated path (same as the WS handshake).</li>
 *   <li>{@code PATCH /matches/{id}} {@code { starred }} -- toggles the star, returns the updated row.</li>
 *   <li>{@code GET /buckets/counts} -- one count per library bucket.</li>
 * </ul>
 */
@RestController
public class MatchController {

    private final MatchRepository matches;
    private final MarkerRepository markers;
    private final PauseRepository pauses;

    public MatchController(MatchRepository matches, MarkerRepository markers, PauseRepository pauses) {
        this.matches = matches;
        this.markers = markers;
        this.pauses = pauses;
    }

    @GetMapping("/matches")
    public List<MatchSummary> matches(
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        // No filters at all -> the original full-list behavior.
        if (isBlank(bucket) && isBlank(result) && isBlank(q) && from == null && to == null) {
            return matches.findAll();
        }
        return matches.findMatches(bucket, result, q, from, to);
    }

    @GetMapping("/matches/{id}")
    public MatchSummary match(@PathVariable long id) {
        return matches.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No match " + id));
    }

    @GetMapping("/matches/{id}/markers")
    public List<MarkerRow> markers(@PathVariable long id) {
        requireMatch(id);
        return markers.findByMatchId(id);
    }

    @GetMapping("/matches/{id}/pauses")
    public List<PauseSpan> pauses(@PathVariable long id) {
        requireMatch(id);
        return pauses.findByMatchId(id);
    }

    /**
     * Resolves the playable video for a match. 404 (with a reason) when the match is unknown or its
     * video has been pruned by retention ({@code video_path} null) -- the player shows a
     * "recording removed" state rather than a broken video element.
     */
    @GetMapping("/matches/{id}/video")
    public VideoLocation video(@PathVariable long id) {
        MatchSummary m = matches.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No match " + id));
        String path = m.videoPath();
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Video for match " + id + " is unavailable (pruned by retention or never recorded)");
        }
        return new VideoLocation(id, path, new File(path).toURI().toString());
    }

    /**
     * Streams the recorded VOD bytes for a match with HTTP Range support so a renderer
     * {@code <video>} element can play and seek over the authed loopback bridge (no cross-origin
     * {@code file://} load). 404 (same reasons as {@link #video}) when the match is unknown, its
     * {@code video_path} is null/blank (pruned by retention / never recorded), or the file is gone
     * from disk. A {@code Range} header yields 206 + {@code Content-Range}; its absence yields 200 +
     * {@code Accept-Ranges: bytes}; an unsatisfiable range yields 416. The body is streamed in 64KB
     * chunks via {@link StreamingResponseBody} (no whole-file buffering).
     */
    @GetMapping("/matches/{id}/video/stream")
    public ResponseEntity<StreamingResponseBody> videoStream(
            @PathVariable long id, @RequestHeader HttpHeaders headers) {
        MatchSummary m = matches.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No match " + id));
        String path = m.videoPath();
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Video for match " + id + " is unavailable (pruned by retention or never recorded)");
        }
        Path file = new File(path).toPath();
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Video for match " + id + " is unavailable (file missing on disk)");
        }

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
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    @PatchMapping("/matches/{id}")
    public MatchSummary patch(@PathVariable long id, @RequestBody MatchPatch patch) {
        // Touch the row first so an unknown id is a clean 404 rather than a silent 0-row update.
        requireMatch(id);
        if (patch != null && patch.starred() != null) {
            matches.setStarred(id, patch.starred());
        }
        return matches.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No match " + id));
    }

    @GetMapping("/buckets/counts")
    public BucketCounts bucketCounts() {
        return BucketCounts.of(matches.bucketCounts());
    }

    private void requireMatch(long id) {
        if (matches.findById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No match " + id);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** {@code GET /matches/{id}/video} response: the absolute path plus a {@code file://} URL. */
    public record VideoLocation(long matchId, String path, String url) {}

    /** {@code PATCH /matches/{id}} body. Only {@code starred} is supported for now; null = no change. */
    public record MatchPatch(Boolean starred) {}
}
