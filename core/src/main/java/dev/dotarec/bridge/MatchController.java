package dev.dotarec.bridge;

import dev.dotarec.data.Bucket;
import dev.dotarec.data.ClipRepository;
import dev.dotarec.data.ClipRow;
import dev.dotarec.data.MarkerRepository;
import dev.dotarec.data.MarkerRow;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchSummary;
import dev.dotarec.data.PauseRepository;
import dev.dotarec.data.PauseSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
 *   <li>{@code GET /matches/{id}/video/stream} -- the recorded VOD bytes over the authed loopback
 *       bridge so a renderer {@code <video>} element can play + seek without a cross-origin
 *       {@code file://} load. Honors HTTP {@code Range}: a {@code Range} header yields 206 Partial
 *       Content with a {@code Content-Range} (Chromium's seek path), an absent header yields 200
 *       with {@code Accept-Ranges: bytes} and the full body, an unsatisfiable range yields 416.
 *       404 when the row is missing, its {@code video_path} is null (pruned/never recorded), or the
 *       file is gone from disk. A {@code <video src>} can't set the {@code X-Dotarec-Token} header, so the
 *       renderer passes the bridge token on the {@code ?token=} query param -- {@code BridgeAuthFilter}
 *       already accepts the token there for every gated path (same as the WS handshake).</li>
 *   <li>{@code PATCH /matches/{id}} {@code { starred }} -- toggles the star, returns the updated row.</li>
 *   <li>{@code GET /buckets/counts} -- one count per library bucket.</li>
 * </ul>
 */
@RestController
public class MatchController {

    private static final Logger log = LoggerFactory.getLogger(MatchController.class);

    private final MatchRepository matches;
    private final MarkerRepository markers;
    private final PauseRepository pauses;
    private final ClipRepository clips;

    public MatchController(MatchRepository matches, MarkerRepository markers, PauseRepository pauses,
                           ClipRepository clips) {
        this.matches = matches;
        this.markers = markers;
        this.pauses = pauses;
        this.clips = clips;
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
     * Streams the recorded VOD bytes for a match with HTTP Range support so a renderer
     * {@code <video>} element can play and seek over the authed loopback bridge (no cross-origin
     * {@code file://} load). 404 when the match is unknown, its
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
        return VideoStreamSupport.stream(file, headers);
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

    /**
     * Permanently deletes a match: the {@code .mp4} + thumbnail on disk, every child clip's
     * {@code .mp4}/thumbnail on disk, then the row (its markers, pauses, and clip rows cascade via the
     * FK). 404 when the id is unknown. File unlinks are best-effort — a missing or locked file is logged
     * and never blocks the row delete (so a half-pruned recording can still be removed). No undo.
     */
    @DeleteMapping("/matches/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        MatchSummary m = matches.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No match " + id));
        deleteFileQuietly(m.videoPath());
        deleteFileQuietly(m.thumbPath());
        // Clip rows cascade-delete with the match (FK ON DELETE CASCADE); their on-disk files do not,
        // so unlink them here to avoid orphaning .mp4/thumb bytes the cascade leaves behind.
        for (ClipRow clip : clips.findByParentMatchId(id)) {
            deleteFileQuietly(clip.videoPath());
            deleteFileQuietly(clip.thumbPath());
        }
        matches.delete(id);
    }

    /** Best-effort unlink: ignores a null/blank/missing path; logs (never throws) on an I/O failure. */
    private static void deleteFileQuietly(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(new File(path).toPath());
        } catch (IOException e) {
            log.warn("Could not delete file {} while deleting a match: {}", path, e.toString());
        }
    }

    @GetMapping("/buckets/counts")
    public BucketCounts bucketCounts() {
        // Clips live in their own table (not as matches rows), so the matches-derived counts always
        // report 0 for the Clips bucket. Override that key with the real clip count so the sidebar
        // Clips pill reflects the clips library.
        Map<String, Integer> counts = matches.bucketCounts();
        counts.put(Bucket.CLIPS.key(), (int) clips.count());
        return BucketCounts.of(counts);
    }

    private void requireMatch(long id) {
        if (matches.findById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No match " + id);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** {@code PATCH /matches/{id}} body. Only {@code starred} is supported for now; null = no change. */
    public record MatchPatch(Boolean starred) {}
}
