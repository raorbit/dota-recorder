package dev.dotarec.bridge;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.config.SettingsStore.AudioSource;
import dev.dotarec.config.SettingsStore.Settings;
import dev.dotarec.config.SettingsStore.StorageLocation;
import dev.dotarec.config.StorageRoots;
import dev.dotarec.gsi.GsiPayload;
import dev.dotarec.obs.ObsController;
import dev.dotarec.obs.ObsSceneConfigurer;
import dev.dotarec.obs.setup.ObsConfigWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
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
    // Sourced from the same contract-kind set the reconcile/enumeration paths map, so a validated kind
    // is provably one the reconciler can turn into an OBS input.
    private static final Set<String> ALLOWED_AUDIO_KIND = ObsSceneConfigurer.CONTRACT_AUDIO_KINDS;

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
        // accountId is the 32-bit Dota account id (the low half of a SteamID), valid range 1..2^32-1.
        // The renderer sends it via Number(), which silently corrupts a pasted 64-bit SteamID to an
        // imprecise float; reject an out-of-range value server-side too so a non-UI caller (or a UI bug)
        // cannot persist a wrong id the tagger then keys the player's own kills/deaths off of. Only an
        // actual incoming value is checked (null = leave unchanged, and clearAccountId wins).
        if (!Boolean.TRUE.equals(patch.clearAccountId())
                && patch.accountId() != null
                && !GsiPayload.isValidAccountId(patch.accountId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "invalid account id: " + patch.accountId() + " (expected a 32-bit Dota account id)");
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
        // A non-null-but-blank videoDir would be persisted as "" and survive until the next core
        // restart's default backfill, during which OBS, thumbnails, and the archiver disagree about
        // where recordings live. Reject it like the other storage-affecting fields rather than persist
        // a blank the three subsystems each interpret differently.
        if (patch.videoDir() != null && patch.videoDir().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "video directory must not be blank");
        }
        // Storage paths are handled in two phases so the store's monitor is NEVER held across a
        // filesystem touch: settings.get() is synchronized on the same monitor and runs on every
        // ~10Hz GSI frame (GsiController.authorized) BEFORE the heartbeat is credited, and a probe
        // against an unreachable UNC/NAS/unplugged-USB path can block for tens of seconds per call in
        // Windows SMB timeouts — long enough for the 30s ForceStopWatchdog to cut a live recording.
        //
        // Phase 1 (here, OUTSIDE the lock): pre-check the pure overlap rule against a snapshot so an
        // overlapping pair is rejected before any directory is created, then run the filesystem
        // checks (trim; absolute; not an existing file; creatable + writable) — skipped ONLY for a
        // path that keeps serving the same ACTIVE role it already holds (see currentVideoDirRoot /
        // currentActiveRoots). Such a path was probed when it became active and may legitimately sit
        // on a currently-offline drive (a state the sweeper/archiver deliberately tolerate), and the
        // renderer re-sends the FULL videoDir + storageLocations on every PUT — so re-sending a
        // stored path must never 400 an unrelated save. A path known only as a HISTORICAL root
        // (previousVideoDirs/previousArchiveDirs) is probed like any new path: history proves the
        // folder existed once, not that it still does, and a PUT promoting it back to an active role
        // must fail loudly rather than persist a dir OBS/the archiver cannot write. OBS (cwd
        // obs/bin/64bit) and the JVM resolve relative paths against different working directories,
        // splitting recordings from playback/retention, so the pure shape checks (incl. absoluteness)
        // still run on every incoming path.
        //
        // Phase 2 (inside store.update): only the cheap pure checks re-run, against the authoritative
        // copy. A PUT interleaving between probe and mutator is a benign TOCTOU — the probes are
        // usability checks, not invariants, and the in-lock pure validation still holds the line.
        Settings snapshot = store.get();
        if (patch.storageLocations() != null) {
            validateStorageLocations(
                    patch.storageLocations(),
                    patch.videoDir() != null ? patch.videoDir() : snapshot.videoDir);
        } else if (patch.videoDir() != null) {
            validateStorageLocations(snapshot.storageLocations, patch.videoDir());
        }
        final String incomingVideoDir =
                patch.videoDir() == null
                        ? null
                        : prepareStoragePath(
                                patch.videoDir(), "video directory", currentVideoDirRoot(snapshot));
        final List<StorageLocation> incomingArchives =
                patch.storageLocations() == null
                        ? null
                        : prepareStorageLocations(
                                patch.storageLocations(), currentActiveRoots(snapshot));
        // Atomic read-copy-mutate: only the user-facing fields are overlaid (non-null), so the
        // app-managed OBS fields (host/port/password) carry forward untouched rather than being
        // reset to defaults.
        store.update(
                current -> {
                    // The storage-overlap rule re-runs INSIDE the store's synchronized update, against
                    // the authoritative copy, so a concurrent PUT can't slip a nested videoDir /
                    // archive pair between the snapshot pre-check and the mutation. Pure string
                    // checks only — the filesystem probes already ran outside the lock. A thrown 400
                    // aborts before anything is persisted.
                    if (patch.storageLocations() != null) {
                        validateStorageLocations(patch.storageLocations(),
                                patch.videoDir() != null ? patch.videoDir() : current.videoDir);
                    } else if (patch.videoDir() != null) {
                        // A videoDir-only PUT doesn't carry storageLocations, but the new dir must
                        // still not overlap/nest the ALREADY-STORED archive locations (else it
                        // double-counts usage and degenerates the archiver).
                        validateStorageLocations(current.storageLocations, patch.videoDir());
                    }
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
                    if (incomingVideoDir != null && !incomingVideoDir.equals(current.videoDir)) {
                        // Retain the outgoing dir so recordings written under it stay streamable +
                        // deletable after the move (their rows keep absolute paths under the old folder).
                        // Assign the new dir FIRST so recordPreviousVideoDir's "skip the current dir"
                        // guard compares the old dir against the NEW videoDir, not against itself.
                        String outgoing = current.videoDir;
                        current.videoDir = incomingVideoDir;
                        SettingsStore.recordPreviousVideoDir(current, outgoing);
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
                    // (single-drive), [..] = replace the whole archive-drive list. A path the replace
                    // removes (or edits away) is retained as a historical archive root so VODs already
                    // moved onto it stay streamable + deletable; a retained path that became an active
                    // root again is dropped.
                    if (incomingArchives != null) {
                        List<StorageLocation> outgoing = current.storageLocations;
                        current.storageLocations = incomingArchives;
                        SettingsStore.recordPreviousArchiveDirs(current, outgoing);
                    } else if (incomingVideoDir != null) {
                        // A videoDir-only PUT can land the recording dir ON a retained archive dir —
                        // it is an active root again, so the retained entry must drop.
                        SettingsStore.recordPreviousArchiveDirs(current, null);
                    }
                    if (patch.autoClipOnRampage() != null) {
                        current.autoClipOnRampage = patch.autoClipOnRampage();
                    }
                    // Clamp clipPaddingSeconds to [1,60] rather than reject: out-of-range padding only
                    // widens/narrows a clip, never breaks recording like a bad fps/quality would.
                    if (patch.clipPaddingSeconds() != null) {
                        current.clipPaddingSeconds = Math.max(1, Math.min(60, patch.clipPaddingSeconds()));
                    }
                    if (patch.recordDemoMatches() != null) {
                        current.recordDemoMatches = patch.recordDemoMatches();
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
        // Unlike the audio reconcile above, this is a local disk write that must NOT be swallowed: a
        // failure leaves an unchanged (or, absent atomicity, corrupt) profile that configure() only
        // re-runs at boot, so OBS silently keeps recording with the old profile for the rest of the
        // session. writeProfile is now atomic, so on-disk basic.ini is always whole (old or new); we
        // still surface the failure (500) instead of returning 200, so a failed reconfigure is visible.
        // The settings were persisted above, so a retry (or the next boot) still picks them up.
        try {
            obsConfigWriter.applyProfile();
        } catch (Exception e) {
            log.error("Profile re-write after settings PUT failed: {}", e.toString(), e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "settings saved but the OBS recording profile could not be updated");
        }
        return SettingsView.of(store.get());
    }

    /**
     * Validates a full-list-replace of {@code storageLocations}: each path non-blank, each
     * {@code capGb > 0}, and every path distinct from AND not nested within the others or the active
     * recording directory ({@code activeDir}: the incoming videoDir when the same PUT changes it, else
     * the stored one — resolved by the caller inside the store's update so the check is atomic with
     * the mutation).
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
    private void validateStorageLocations(List<StorageLocation> locations, String activeDir) {
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
     * parent of {@code inner}). Delegates the trailing-separator prefix to {@link StorageRoots#prefix} so
     * {@code D:\rec} matches {@code D:\rec\archive} but not a sibling {@code D:\record} — identical to the
     * attribution-side prefix logic.
     */
    private static boolean contains(String outer, String inner) {
        return inner.startsWith(StorageRoots.prefix(outer));
    }

    /**
     * Probe-skip set for an incoming {@code videoDir}: the canonical ({@link #normalizePath}) form
     * of the CURRENT active recording directory, and nothing else. The renderer re-sends the full
     * videoDir on every PUT and the active dir may sit on a temporarily-offline drive, so an
     * UNCHANGED re-send must not fail an unrelated save with a fresh probe. But ANY canonically
     * different incoming dir is about to become the folder OBS records into and is ALWAYS probed —
     * including one matching a historical root ({@code previousVideoDirs}/{@code
     * previousArchiveDirs}) or a stored archive location. Historical roots are offline-tolerant
     * READ+DELETE roots: nothing guarantees their folder still exists (the user may have deleted the
     * emptied dir in Explorer long after the move), and persisting an absent ACTIVE dir unprobed
     * writes a nonexistent RecFilePath into the OBS profile, so StartRecord fails and
     * OUTPUT_STARTED never fires — every match silently unrecorded.
     */
    private static Set<String> currentVideoDirRoot(Settings s) {
        Set<String> known = new java.util.HashSet<>();
        addKnownPath(known, s.videoDir);
        return known;
    }

    /**
     * Probe-skip set for incoming {@code storageLocations}: the canonical forms of the paths
     * CURRENTLY serving as active roots — the active videoDir plus each stored archive location. A
     * re-sent archive was probed when it became active and may legitimately sit on an unplugged
     * drive the sweeper/archiver deliberately tolerate; the videoDir entry covers the same PUT
     * moving the recording dir OFF a folder while adding that folder as an archive (it is the live
     * recording target, so it provably exists). Historical roots are deliberately NOT in this set:
     * re-ADDING a retired root makes it an archiver TARGET again, and the archiver cannot self-heal
     * a missing folder — {@code RecordingArchiver.firstWithHeadroom}'s free-space probe
     * ({@code Files.getFileStore}) throws on a nonexistent directory and skips the drive before
     * {@code moveToLocation}'s createDirectories is ever reached — so an unprobed absent folder
     * would be persisted and then silently never receive a single file. Probe it like a new path:
     * the folder is recreated eagerly, or the PUT fails 400 while the drive is genuinely offline.
     */
    private static Set<String> currentActiveRoots(Settings s) {
        Set<String> known = currentVideoDirRoot(s);
        if (s.storageLocations != null) {
            for (StorageLocation loc : s.storageLocations) {
                if (loc != null) {
                    addKnownPath(known, loc.path());
                }
            }
        }
        return known;
    }

    /** Adds the canonical form of a non-blank stored path to {@code known}. */
    private static void addKnownPath(Set<String> known, String path) {
        if (path != null && !path.isBlank()) {
            known.add(normalizePath(path));
        }
    }

    /** {@link #prepareStoragePath} over a full incoming storageLocations replace-list. */
    private static List<StorageLocation> prepareStorageLocations(
            List<StorageLocation> locations, Set<String> probeSkip) {
        List<StorageLocation> prepared = new java.util.ArrayList<>(locations.size());
        for (StorageLocation loc : locations) {
            // Null/blank entries were already rejected by validateStorageLocations above.
            prepared.add(new StorageLocation(
                    loc.id(),
                    prepareStoragePath(loc.path(), "storage location", probeSkip),
                    loc.capGb()));
        }
        return prepared;
    }

    /**
     * Trims and shape-checks an incoming storage path, filesystem-probing it UNLESS its canonical
     * form is in {@code probeSkip} — the role-specific set of paths still serving the same active
     * role they already hold ({@link #currentVideoDirRoot} for videoDir, {@link #currentActiveRoots}
     * for archive locations), which are persisted untouched. Runs BEFORE {@code store.update()}: the
     * probe can block for tens of seconds on an unreachable network/USB path, and holding the store
     * monitor that long stalls the GSI heartbeat until the ForceStopWatchdog cuts a live recording.
     */
    private static String prepareStoragePath(String rawPath, String label, Set<String> probeSkip) {
        String trimmed = requireWellFormedDirectory(rawPath, label);
        if (!probeSkip.contains(normalizePath(trimmed))) {
            probeUsableDirectory(trimmed, label);
        }
        return trimmed;
    }

    /**
     * The pure shape rules for a storage path — trim; non-blank; parseable; absolute (OBS runs with
     * cwd {@code obs/bin/64bit} while the JVM resolves against its own working dir, so a relative
     * path would record to one folder and play back/retain from another). Returns the trimmed path
     * for persistence or throws a 400. Deliberately touches NO filesystem: it also runs for
     * already-stored paths, which may live on a temporarily-offline drive.
     */
    private static String requireWellFormedDirectory(String rawPath, String label) {
        String trimmed = rawPath == null ? "" : rawPath.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, label + " must not be blank");
        }
        Path dir;
        try {
            dir = Path.of(trimmed);
        } catch (InvalidPathException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, label + " is not a valid path: " + trimmed);
        }
        if (!dir.isAbsolute()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    label + " must be an absolute path (was: " + trimmed + ")");
        }
        return trimmed;
    }

    /**
     * Filesystem probe for a NEW or EDITED storage path: not an existing regular file, and an
     * existing-or-creatable, writable directory — probed by creating and immediately deleting a temp
     * file. Creating the directory here is deliberate: it is non-destructive, and it fails the PUT at
     * save time instead of at the first recording. Must only run OUTSIDE the settings store's lock
     * and never for a path still serving the same active role (see {@link #prepareStoragePath}).
     */
    private static void probeUsableDirectory(String trimmed, String label) {
        Path dir = Path.of(trimmed);
        if (Files.isRegularFile(dir)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    label + " points at an existing file, not a folder: " + trimmed);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException | RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    label + " does not exist and could not be created: " + trimmed);
        }
        try {
            Path probe = Files.createTempFile(dir, ".dotarec-write-probe", ".tmp");
            Files.deleteIfExists(probe);
        } catch (IOException | RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, label + " is not writable: " + trimmed);
        }
    }

    /**
     * Best-effort path normalization for distinctness/containment checks (Windows paths are
     * case-insensitive). Delegates to {@link StorageRoots#normalize} — the same canonical form the
     * byte-attribution code ({@code RecordingArchiver.locationOf}) and the stream guard
     * ({@code StorageRoots.isUnder}) use. The leading {@link String#trim()} strips stray whitespace off
     * user-typed archive paths before canonicalizing; {@code toAbsolutePath()} inside {@code normalize}
     * keeps a relative archive path (e.g. {@code "."}) from canonicalizing differently here than at
     * attribution time, which would let it pass this distinctness check yet still resolve onto the active
     * recording dir at move time (self-move).
     */
    private static String normalizePath(String path) {
        try {
            return StorageRoots.normalize(path.trim());
        } catch (RuntimeException e) {
            return path.trim().toLowerCase(java.util.Locale.ROOT);
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
            List<StorageLocation> storageLocations,
            boolean autoClipOnRampage,
            int clipPaddingSeconds,
            boolean recordDemoMatches) {

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
                    s.storageLocations,
                    s.autoClipOnRampage,
                    s.clipPaddingSeconds,
                    s.recordDemoMatches);
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
            List<StorageLocation> storageLocations,
            Boolean autoClipOnRampage,
            Integer clipPaddingSeconds,
            Boolean recordDemoMatches) {}
}
