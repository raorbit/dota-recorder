package dev.dotarec.bridge;

import dev.dotarec.data.MarkerRepository;
import dev.dotarec.data.MarkerRow;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchSummary;
import dev.dotarec.data.PauseRepository;
import dev.dotarec.data.PauseSpan;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.util.List;

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
 *       when the row is missing or its video was pruned ({@code video_path} null).</li>
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
