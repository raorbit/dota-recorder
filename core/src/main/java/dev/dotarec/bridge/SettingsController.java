package dev.dotarec.bridge;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.config.SettingsStore.AudioSource;
import dev.dotarec.config.SettingsStore.Settings;
import dev.dotarec.config.SettingsStore.StorageLocation;
import dev.dotarec.obs.ObsController;
import dev.dotarec.obs.setup.ObsConfigWriter;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Settings endpoint consumed by the Electron settings UI over the loopback bridge.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li>{@code GET /settings} -> 200 with {@link SettingsView}. The OBS WebSocket connection
 *       (host/port/password) is app-managed and not part of the user-facing surface, so it is not
 *       exposed here.</li>
 *   <li>{@code PUT /settings} -> 200 with the updated {@link SettingsView}. The body is a
 *       <em>partial</em> update ({@link SettingsPatch}): any field left null is preserved, so the
 *       UI can submit just the fields it changed. The app-managed OBS fields are never touched by
 *       this endpoint.</li>
 * </ul>
 */
@RestController
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    // Supported recording-control values, mirroring the renderer's pickers (RecordingSettings.tsx).
    // A bad fps/quality/format writes a broken OBS profile, which reproduces the "no OUTPUT_STARTED ->
    // every match aborts" failure this branch fixed -- so reject (400) rather than silently persist.
    private static final Set<Integer> ALLOWED_FPS = Set.of(30, 60);
    private static final Set<String> ALLOWED_QUALITY = Set.of("Stream", "HQ", "Lossless", "Small");
    private static final Set<String> ALLOWED_FORMAT =
            Set.of("hybrid_mp4", "fragmented_mp4", "mkv", "mov");
    // Audio sources are also OBS-affecting: an unknown kind makes reconcile skip the source, which can
    // leave isReady() false ("records nothing"). Validate it like the other OBS-affecting fields.
    private static final Set<String> ALLOWED_AUDIO_KIND = Set.of("application", "output", "input");

    private final SettingsStore store;
    private final ObsController obsController;
    private final ObsConfigWriter obsConfigWriter;

    public SettingsController(
            SettingsStore store, ObsController obsController, ObsConfigWriter obsConfigWriter) {
        this.store = store;
        this.obsController = obsController;
        this.obsConfigWriter = obsConfigWriter;
    }

    @GetMapping("/settings")
    public SettingsView getSettings() {
        return SettingsView.of(store.get());
    }

    @PutMapping("/settings")
    public SettingsView putSettings(@RequestBody SettingsPatch patch) {
        // Validate the recording-control fields BEFORE persisting. Each is a partial patch, so only a
        // field the body actually carries (non-null) is checked. A garbage value would write a broken
        // OBS profile and abort every match, so reject with 400 rather than clamp.
        if (patch.fps() != null && !ALLOWED_FPS.contains(patch.fps())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid fps: " + patch.fps() + " (allowed: " + ALLOWED_FPS + ")");
        }
        if (patch.quality() != null && !ALLOWED_QUALITY.contains(patch.quality())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "invalid quality: " + patch.quality() + " (allowed: " + ALLOWED_QUALITY + ")");
        }
        if (patch.format() != null && !ALLOWED_FORMAT.contains(patch.format())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "invalid format: " + patch.format() + " (allowed: " + ALLOWED_FORMAT + ")");
        }
        if (patch.audioSources() != null) {
            for (SettingsStore.AudioSource s : patch.audioSources()) {
                if (s == null || !ALLOWED_AUDIO_KIND.contains(s.kind())) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "invalid audio source kind: "
                                    + (s == null ? "null" : s.kind())
                                    + " (allowed: " + ALLOWED_AUDIO_KIND + ")");
                }
                if (s.volume() < 0 || s.volume() > 100) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "invalid audio source volume: " + s.volume() + " (0..100)");
                }
            }
        }
        // The active-drive retention cap must stay positive, mirroring the per-archive cap check in
        // validateStorageLocations. Without this a cleared "Max storage" field (which the UI sends as
        // 0) would persist retentionCapGb=0 and starve the sweeper's budget.
        if (patch.retentionCapGb() != null && patch.retentionCapGb() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "retention cap must be > 0 GB (was " + patch.retentionCapGb() + ")");
        }
        if (patch.storageLocations() != null) {
            validateStorageLocations(patch.storageLocations(), patch.videoDir());
        }
        // Atomic read-copy-mutate: only the user-facing fields are overlaid (non-null), so the
        // app-managed OBS fields (host/port/password) carry forward untouched rather than being
        // reset to defaults.
        store.update(
                current -> {
                    if (patch.resolution() != null) {
                        current.resolution = patch.resolution();
                    }
                    if (patch.encoder() != null) {
                        current.encoder = patch.encoder();
                    }
                    if (patch.fps() != null) {
                        current.fps = patch.fps();
                    }
                    if (patch.quality() != null) {
                        current.quality = patch.quality();
                    }
                    if (patch.format() != null) {
                        current.format = patch.format();
                    }
                    if (patch.retentionCapGb() != null) {
                        current.retentionCapGb = patch.retentionCapGb();
                    }
                    if (patch.videoDir() != null) {
                        current.videoDir = patch.videoDir();
                    }
                    // accountId also uses null = "leave unchanged", so clearing it needs an explicit
                    // flag (a blanked Account ID field in the UI sends clearAccountId=true).
                    if (Boolean.TRUE.equals(patch.clearAccountId())) {
                        current.accountId = null;
                    } else if (patch.accountId() != null) {
                        current.accountId = patch.accountId();
                    }
                    // audioSources is a FULL-LIST REPLACE: null = leave unchanged, [] = clear all,
                    // [..] = replace the entire stored list. No per-element merge, no clear-flag — the
                    // renderer always sends the complete current array on any edit/add/remove.
                    if (patch.audioSources() != null) {
                        current.audioSources = patch.audioSources();
                    }
                    // storageLocations is a FULL-LIST REPLACE too: null = leave unchanged, [] = clear
                    // (single-drive), [..] = replace the whole archive-drive list.
                    if (patch.storageLocations() != null) {
                        current.storageLocations = patch.storageLocations();
                    }
                    return current;
                });
        // Apply the (possibly new) audio source list to a live OBS without waiting for a reconnect.
        // Best-effort: the persisted settings are the source of truth and the next disconnect->connect
        // edge re-reconciles, so an OBS-down or transient failure here must never 500 the PUT.
        try {
            obsController.reconcileAudioOnDemand();
        } catch (Exception e) {
            log.debug("On-demand audio reconcile after settings PUT failed (OBS down?): {}", e.toString());
        }
        // Re-write basic.ini from the saved settings so the recording profile (fps/quality/format/
        // encoder/resolution) is fresh for the NEXT OBS launch instead of stale until the next reboot.
        // Best-effort and side-effect-free (no copy/probe/websocket rewrite); the saved settings are the
        // source of truth and boot-time configure() is authoritative, so a failure here must never 500.
        try {
            obsConfigWriter.applyProfile();
        } catch (Exception e) {
            log.debug("Profile re-write after settings PUT failed: {}", e.toString());
        }
        return SettingsView.of(store.get());
    }

    /**
     * Validates a full-list-replace of {@code storageLocations}: each path non-blank, each
     * {@code capGb > 0}, and every path distinct from AND not nested within the others or the active
     * recording directory ({@code patchVideoDir} when the same PUT also changes it, else the stored
     * one).
     *
     * <p>Both exact duplicates and parent/child containment are rejected. An archive drive pointed at
     * the active dir would make the archiver move a file onto itself; a nested pair (one path a prefix
     * of another, e.g. {@code D:\rec} and {@code D:\rec\archive}) is just as bad — bytes under the
     * inner dir are counted toward BOTH locations in {@code StorageController.usage}, and the archiver
     * keeps attributing the same file to two drives, producing recurring no-op self-moves. Containment
     * is tested on the canonical form with a trailing {@link java.io.File#separator} appended, matching
     * {@code RecordingArchiver.locationOf}/{@code StorageController.prefix}, so {@code D:\rec} matches
     * {@code D:\rec\archive} but NOT a sibling {@code D:\record}.
     *
     * <p>A cap that exceeds the drive's physical capacity is intentionally NOT rejected here: it's
     * warn-only in the UI, matching the "free-space check warns, never blocks" posture.
     */
    private void validateStorageLocations(List<StorageLocation> locations, String patchVideoDir) {
        String activeDir = patchVideoDir != null ? patchVideoDir : store.get().videoDir;
        // Accumulate the canonical form of every path accepted so far (the active recording dir plus
        // each validated archive path) and test every new path against all of them for either-direction
        // containment. A plain Set would catch only exact duplicates, not nesting.
        List<String> accepted = new java.util.ArrayList<>();
        if (activeDir != null && !activeDir.isBlank()) {
            accepted.add(normalizePath(activeDir));
        }
        for (StorageLocation loc : locations) {
            if (loc == null || loc.path() == null || loc.path().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "storage location path must not be blank");
            }
            if (loc.capGb() <= 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "storage location cap must be > 0 GB (was " + loc.capGb() + " for " + loc.path() + ")");
            }
            String candidate = normalizePath(loc.path());
            for (String existing : accepted) {
                if (candidate.equals(existing) || contains(existing, candidate) || contains(candidate, existing)) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "storage location overlaps another folder (duplicate, nested, or the recording"
                                    + " folder): " + loc.path());
                }
            }
            accepted.add(candidate);
        }
    }

    /**
     * True when canonical path {@code outer} contains {@code inner} (i.e. {@code outer} is a strict
     * parent of {@code inner}). Appends a trailing {@link java.io.File#separator} to {@code outer}
     * before the prefix test so {@code D:\rec} matches {@code D:\rec\archive} but not a sibling
     * {@code D:\record} — identical to the attribution-side prefix logic.
     */
    private static boolean contains(String outer, String inner) {
        String prefix = outer.endsWith(java.io.File.separator) ? outer : outer + java.io.File.separator;
        return inner.startsWith(prefix);
    }

    /**
     * Best-effort path normalization for distinctness/containment checks (Windows paths are
     * case-insensitive). MUST match the canonical form used by the byte-attribution code
     * ({@code RecordingArchiver.locationOf} and {@code StorageController.normalize}):
     * {@code toAbsolutePath().normalize().toString().toLowerCase()}. Without {@code toAbsolutePath()}
     * a relative archive path (e.g. {@code "."}) would canonicalize differently here than at
     * attribution time, so it could pass this distinctness check yet still resolve onto the active
     * recording dir at move time (self-move). Keeping both forms identical closes that drift.
     */
    private static String normalizePath(String path) {
        try {
            return java.nio.file.Path.of(path.trim()).toAbsolutePath().normalize().toString().toLowerCase();
        } catch (RuntimeException e) {
            return path.trim().toLowerCase();
        }
    }

    /**
     * Read view of settings. The app-managed OBS connection (host/port/password) is intentionally
     * omitted. Null fields are still serialized so the UI sees a stable shape.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SettingsView(
            String resolution,
            String encoder,
            int retentionCapGb,
            String videoDir,
            Long accountId,
            List<AudioSource> audioSources,
            int fps,
            String quality,
            String format,
            List<StorageLocation> storageLocations) {

        static SettingsView of(Settings s) {
            return new SettingsView(
                    s.resolution,
                    s.encoder,
                    s.retentionCapGb,
                    s.videoDir,
                    s.accountId,
                    s.audioSources,
                    s.fps,
                    s.quality,
                    s.format,
                    s.storageLocations);
        }
    }

    /**
     * Partial update body. Every field is nullable; null means "leave unchanged". Wrapper types
     * (not {@code int}) so an omitted {@code retentionCapGb} is distinguishable from an explicit 0.
     * {@code clearAccountId=true} is the explicit "set accountId to null" signal, since a null
     * {@code accountId} (like every other field) means "leave unchanged".
     */
    public record SettingsPatch(
            String resolution,
            String encoder,
            Integer retentionCapGb,
            String videoDir,
            Long accountId,
            Boolean clearAccountId,
            List<AudioSource> audioSources,
            Integer fps,
            String quality,
            String format,
            List<StorageLocation> storageLocations) {}
}
