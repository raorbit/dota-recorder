package dev.dotarec.bridge;

import dev.dotarec.clip.ClipService;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.config.SettingsStore.StorageLocation;
import dev.dotarec.data.ClipRepository;
import dev.dotarec.data.ClipRow;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.retention.StorageMaintenanceLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.ArrayList;
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
    private final SettingsStore settings;
    private final StorageMaintenanceLock maintenanceLock;

    @Autowired
    public ClipController(ClipRepository clips, ClipService clipService, MatchRepository matches,
                          SettingsStore settings, StorageMaintenanceLock maintenanceLock) {
        this.clips = clips;
        this.clipService = clipService;
        this.matches = matches;
        this.settings = settings;
        this.maintenanceLock = maintenanceLock;
    }

    /**
     * Backward-compatible constructor for existing tests that drive the controller with no concurrent
     * archiver/sweeper: defaults a fresh {@link StorageMaintenanceLock} (a private instance is fine
     * when nothing else contends for it).
     */
    public ClipController(ClipRepository clips, ClipService clipService, MatchRepository matches,
                          SettingsStore settings) {
        this(clips, clipService, matches, settings, new StorageMaintenanceLock());
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
        long clipId;
        try {
            clipId = clipService.createManual(id, req.startOffsetS(), req.endOffsetS(), req.label());
        } catch (IllegalArgumentException e) {
            // ClipService rejects any invalid input (degenerate range, non-finite offset, over-long
            // duration/label, no recorded video) with IllegalArgumentException -> 400 Bad Request.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
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
        if (patch != null && patch.starred() != null) {
            // setStarred returns rows updated; 0 means no such clip -> 404 without a second existence
            // query. (A null/empty patch falls through to a plain re-read, still 404 on an unknown id.)
            if (clips.setStarred(clipId, patch.starred()) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No clip " + clipId);
            }
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
     *
     * <p>Serializes against the storage-maintenance passes ({@link dev.dotarec.retention.RecordingArchiver}
     * archive + {@link dev.dotarec.retention.RetentionSweeper} sweep) via {@link StorageMaintenanceLock}:
     * the archiver treats clips as first-class movable files — it copies a clip cross-store, repoints the
     * row via {@code clips.updateVideoPath}, then deletes the source. Unguarded, this delete could
     * interleave and unlink the now-moved (stale) source while dropping the repointed row, stranding the
     * archived clip for {@code CrashRecoveryRunner} to re-adopt as a spurious match. Under the lock the
     * row's CURRENT paths are RE-READ (by id) immediately before unlinking, so the files removed are
     * always the row's present locations even if the archiver just repointed them.
     */
    @DeleteMapping("/clips/{clipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long clipId) {
        // Existence probe outside the lock so an unknown id is a cheap 404 without serializing on
        // maintenance; the authoritative paths are re-read under the lock below.
        clips.findById(clipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No clip " + clipId));
        maintenanceLock.lock();
        try {
            // Re-read the CURRENT row inside the lock: the archiver may have repointed video_path/
            // thumb_path to an archive drive since the probe above, so unlink the present locations, not
            // the pre-move ones (which would be a no-op leaving the moved file stranded). A row deleted
            // by a concurrent pass leaves nothing to unlink; the row delete below is then a no-op.
            clips.findById(clipId).ifPresent(c -> {
                deleteFileQuietly(c.videoPath());
                deleteFileQuietly(c.thumbPath());
            });
            clips.delete(clipId);
        } finally {
            maintenanceLock.unlock();
        }
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
        requireUnderStorageRoot(file, "Video for clip " + clipId);
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
        requireUnderStorageRoot(file, "Thumbnail for clip " + clipId);
        return VideoStreamSupport.stream(file, headers);
    }

    /**
     * Best-effort unlink: ignores a null/blank/missing path; logs (never throws) on an I/O failure.
     * Mirrors the read-side containment guard — a path outside every configured storage root (a
     * tampered DB row, a {@code ..} escape) is skipped, never unlinked, so a delete can't be coerced
     * into removing an arbitrary file on disk.
     */
    private void deleteFileQuietly(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        Path file = new File(path).toPath();
        if (!VideoStreamSupport.isUnderAnyRoot(file, storageRoots())) {
            log.warn("Skipping delete of {} while deleting a clip: outside the configured storage roots",
                    path);
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete file {} while deleting a clip: {}", path, e.toString());
        }
    }

    /**
     * Path-traversal guard for the file-serving endpoints: a stored path must resolve under one of the
     * configured storage roots ({@code videoDir} + every archive {@code storageLocations[].path}), else
     * 404. Archived clips live under an archive root, so all roots are allowed. {@code what} names the
     * resource for the 404 message (e.g. {@code "Video for clip 7"}).
     */
    private void requireUnderStorageRoot(Path file, String what) {
        if (!VideoStreamSupport.isUnderAnyRoot(file, storageRoots())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    what + " is unavailable (outside the configured storage roots)");
        }
    }

    /** The configured storage roots: the active {@code videoDir} plus every archive drive's path. */
    private List<String> storageRoots() {
        SettingsStore.Settings s = settings.get();
        List<String> roots = new ArrayList<>();
        roots.add(s.videoDir);
        if (s.storageLocations != null) {
            for (StorageLocation loc : s.storageLocations) {
                if (loc != null) {
                    roots.add(loc.path());
                }
            }
        }
        return roots;
    }

    private void requireMatch(long id) {
        if (matches.findById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No match " + id);
        }
    }

    /** {@code POST /matches/{id}/clips} body. {@code label} is optional (null = no label). */
    public record NewClipRequest(double startOffsetS, double endOffsetS, String label) {}
}
