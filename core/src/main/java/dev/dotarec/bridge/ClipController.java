package dev.dotarec.bridge;

import dev.dotarec.clip.ClipService;
import dev.dotarec.data.ClipRepository;
import dev.dotarec.data.ClipRow;
import dev.dotarec.data.MatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Clip endpoints consumed by the Electron browse/player UI over the loopback bridge. A clip is a
 * sub-range of a parent match's recording rendered into its own .mp4 (see {@link ClipService}).
 *
 * <p>Contract:
 * <ul>
 *   <li>{@code GET /clips} -- every clip across all matches, newest first. Backs the library "Clips"
 *       bucket's flat list.</li>
 *   <li>{@code GET /matches/{id}/clips} -- the match's clips, ordered by start offset (timeline
 *       order). 404 when the match is unknown.</li>
 *   <li>{@code POST /matches/{id}/clips} {@code { startOffsetS, endOffsetS, label }} -- carves a
 *       manual clip and dispatches async generation; returns 202 Accepted with the freshly inserted
 *       {@code pending} {@link ClipRow}. 404 when the match is unknown.</li>
 *   <li>{@code DELETE /clips/{clipId}} -- permanently removes a clip: best-effort unlinks the
 *       rendered .mp4 + thumbnail on disk, then the row. 404 when the id is unknown. No undo.</li>
 *   <li>{@code GET /clips/{clipId}/video/stream} -- streams the rendered clip .mp4 bytes with HTTP
 *       Range support so a renderer {@code <video>} element can play + seek over the authed loopback
 *       bridge (mirrors {@code GET /matches/{id}/video/stream}). 404 when the clip is unknown, not yet
 *       {@code ready}/has a null {@code video_path}, or the file is gone from disk. A {@code <video src>}
 *       can't set the {@code X-Dotarec-Token} header, so the renderer passes the bridge token on the
 *       {@code ?token=} query param -- {@code BridgeAuthFilter} already accepts the token there.</li>
 *   <li>{@code GET /clips/{clipId}/thumb} -- streams the clip's generated thumbnail (a single JPEG
 *       frame grab) over the authed loopback bridge, mirroring the clip video stream (ranged via
 *       {@link VideoStreamSupport}, {@code ?token=} auth). 404 when the clip is unknown, its
 *       {@code thumb_path} is null/blank (not yet rendered or thumbnail generation failed), or the
 *       file is gone from disk.</li>
 * </ul>
 */
@RestController
public class ClipController {

    private static final Logger log = LoggerFactory.getLogger(ClipController.class);

    private final ClipRepository clips;
    private final ClipService clipService;
    private final MatchRepository matches;

    public ClipController(ClipRepository clips, ClipService clipService, MatchRepository matches) {
        this.clips = clips;
        this.clipService = clipService;
        this.matches = matches;
    }

    /** Every clip across all matches, newest first — backs the library "Clips" bucket's flat list. */
    @GetMapping("/clips")
    public List<ClipRow> allClips() {
        return clips.findAll();
    }

    @GetMapping("/matches/{id}/clips")
    public List<ClipRow> clips(@PathVariable long id) {
        requireMatch(id);
        return clips.findByParentMatchId(id);
    }

    /**
     * Carves a manual clip out of a match's recording and dispatches its async generation. Returns
     * 202 Accepted with the freshly inserted {@code pending} row — the .mp4 is rendered off-thread and
     * the row flips to {@code ready}/{@code failed} later (the UI follows {@code clip.ready} over the WS).
     */
    @PostMapping("/matches/{id}/clips")
    public ResponseEntity<ClipRow> create(@PathVariable long id, @RequestBody NewClipRequest req) {
        requireMatch(id);
        long clipId = clipService.createManual(id, req.startOffsetS(), req.endOffsetS(), req.label());
        ClipRow row = clips.findById(clipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No clip " + clipId));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(row);
    }

    /**
     * Stars/unstars a clip. A starred clip is exempt from the retention sweep — kept until manually
     * deleted, independent of its parent match's star. Mirrors {@code PATCH /matches/{id}}.
     */
    @PatchMapping("/clips/{clipId}")
    public ClipRow patch(@PathVariable long clipId, @RequestBody ClipPatch patch) {
        clips.findById(clipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No clip " + clipId));
        if (patch != null && patch.starred() != null) {
            clips.setStarred(clipId, patch.starred());
        }
        return clips.findById(clipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No clip " + clipId));
    }

    /** Partial-update body for {@code PATCH /clips/{clipId}}; a null field is left unchanged. */
    public record ClipPatch(Boolean starred) {}

    /**
     * Permanently deletes a clip: the rendered {@code .mp4} + thumbnail on disk, then the row. 404 when
     * the id is unknown. File unlinks are best-effort — a missing or locked file is logged and never
     * blocks the row delete (so a half-rendered clip can still be removed). No undo.
     */
    @DeleteMapping("/clips/{clipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long clipId) {
        ClipRow c = clips.findById(clipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No clip " + clipId));
        deleteFileQuietly(c.videoPath());
        deleteFileQuietly(c.thumbPath());
        clips.delete(clipId);
    }

    /**
     * Streams a rendered clip's .mp4 bytes with HTTP Range support so a renderer {@code <video>}
     * element can play and seek over the authed loopback bridge (mirrors the match VOD stream). 404
     * when the clip is unknown, its {@code video_path} is null/blank (not yet rendered or generation
     * failed), or the file is gone from disk.
     */
    @GetMapping("/clips/{clipId}/video/stream")
    public ResponseEntity<StreamingResponseBody> videoStream(
            @PathVariable long clipId, @RequestHeader HttpHeaders headers) {
        ClipRow c = clips.findById(clipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No clip " + clipId));
        String path = c.videoPath();
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Video for clip " + clipId + " is unavailable (not yet rendered or generation failed)");
        }
        Path file = new File(path).toPath();
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Video for clip " + clipId + " is unavailable (file missing on disk)");
        }
        return VideoStreamSupport.stream(file, headers);
    }

    /**
     * Streams a clip's generated thumbnail (a single JPEG frame grab) over the authed loopback bridge,
     * mirroring the clip video stream (ranged via {@link VideoStreamSupport}, served as
     * {@code image/jpeg}). 404 when the clip is unknown, its {@code thumb_path} is null/blank (not yet
     * rendered or thumbnail generation failed), or the file is gone from disk. A {@code <img src>}
     * can't set the {@code X-Dotarec-Token} header, so the renderer passes the bridge token on the
     * {@code ?token=} query param -- {@code BridgeAuthFilter} already accepts the token there.
     */
    @GetMapping("/clips/{clipId}/thumb")
    public ResponseEntity<StreamingResponseBody> thumb(
            @PathVariable long clipId, @RequestHeader HttpHeaders headers) {
        ClipRow c = clips.findById(clipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No clip " + clipId));
        String path = c.thumbPath();
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Thumbnail for clip " + clipId + " is unavailable (not yet rendered or generation failed)");
        }
        Path file = new File(path).toPath();
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Thumbnail for clip " + clipId + " is unavailable (file missing on disk)");
        }
        return VideoStreamSupport.stream(file, headers);
    }

    /** Best-effort unlink: ignores a null/blank/missing path; logs (never throws) on an I/O failure. */
    private static void deleteFileQuietly(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(new File(path).toPath());
        } catch (IOException e) {
            log.warn("Could not delete file {} while deleting a clip: {}", path, e.toString());
        }
    }

    private void requireMatch(long id) {
        if (matches.findById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No match " + id);
        }
    }

    /** {@code POST /matches/{id}/clips} body. {@code label} is optional (null = no label). */
    public record NewClipRequest(double startOffsetS, double endOffsetS, String label) {}
}
