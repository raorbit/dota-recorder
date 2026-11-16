package dev.dotarec.fsm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-folder adoption floors for {@link CrashRecoveryRunner}'s orphan scan: the time from which a
 * file in a recording or archive root may be adopted (or linked to a crashed session).
 *
 * <p>The scan's basename gate can't tell this recorder's files from the user's: OBS writes its
 * default {@code %CCYY-%MM-%DD %hh-%mm-%ss} names whoever drives it, so a recording or archive
 * folder chosen where the user's own OBS saves (Videos, say) is full of names that pass. An adopted
 * file becomes a match row, and retention then deletes it or moves it to an archive drive. So each
 * root also gets a floor the first time a scan reads it, and nothing modified before it is adopted.
 * That first floor is the scan's start, or just after the newest recorder-named file the folder
 * already holds when that is later: a scan on a clock far behind (a reset CMOS clock before time
 * sync, say) would otherwise set a floor from before the folder's own history, and once the clock
 * is right every file in it would pass. A folder the scan can't read (an unplugged drive) gets no
 * floor until a boot that can read it, since what it holds can't be checked. A boot scan that no
 * longer reads a folder drops its floor, so a folder chosen again, or an archive drive added back,
 * starts from a new floor: what it gained while it wasn't ours is never adopted. A drive that is
 * configured but unplugged is still a root and keeps its floor. Only a boot sees the roots, so a
 * folder left and chosen again within one run keeps its old floor.
 *
 * <p>The cost is lopsided on purpose. An orphan the floor turns away only stays on disk, where the
 * user can still find it; adopting the user's file hands it to deletion. So an upgraded install
 * never adopts what predates the upgrade, a newly chosen folder never adopts what it already held,
 * and when the floors can't be read they start again from now (or from what each folder holds). A
 * floor is set at the first boot scan after a folder is chosen, not when it's chosen, so a crash
 * orphan written there before that boot stays on disk too. What's left: a personal OBS recording
 * made into the same folder after its floor, with OBS's default name, is still adopted.
 *
 * <p>Every 0.1.x before the floors adopted orphans with no floor, so the folders an upgraded install
 * already had may hold the user's own recordings as library rows. Once, on the first boot whose
 * floors are saved, {@link CrashRecoveryRunner} releases them: it removes from the library each
 * unstarred row the import adopted ({@code gsi_only}) before the earliest floor the floors file held
 * when that boot began, or every such row, whenever it was created, when that boot read no floor (no
 * file, or one it couldn't read), unless the row is certainly this app's, and leaves its file where
 * it is, below its folder's floor, as the floors would have. Then it writes {@value
 * #RELEASE_FILE_NAME} next to the floors, exactly {@code
 * {"version":1,"releasedAtMs":<epoch millis>,"rows":<rows released>}}, and never releases again
 * while that file exists, even if the floors file goes. The Go remake can port the step under the
 * same file name and format. A build without the floors can still run on this data folder after
 * they exist (a downgrade, a dev build of an older branch) and adopt what they turn away, so every
 * boot also releases a row created after its folder's floor whose file predates that floor, which no
 * build that keeps the floors adopts.
 *
 * <p>One exception, for linking alone. When the video folder gets its first floor in a boot that
 * recovers an unfinished recording with no journaled path (the upgrade boot after a crash mid-match,
 * say), the link that claims that recording's file may reach back to the earliest such recording's
 * start, less the link window's grace: the journal is evidence this app was recording then, and a
 * floor at the scan would turn away the very file the journal is about to claim. The link is still
 * held to its recording's own window. The reach-back is never stored, and the folder's floor is the
 * scan's like any other, because the floor also gates the import of unclaimed orphans: stored, it
 * would admit every default-named file the user's own OBS wrote there from the crashed match on. So
 * the floors are worked out before the journal is replayed but saved only after it ({@link Plan}):
 * when such a recording is still unrecovered then (its recovery rolled back), the folder's first
 * floor isn't saved and the folder adopts nothing this boot, so the next boot reaches back again.
 * A video folder the scan can't read gets no floor, as above, and the recordings that need it wait
 * in the journal for a boot that can read it. The scan reads the video folder once, floored or not,
 * and that one read decides both ({@link Plan#cantReadVideoDir}): a second look could find a drive
 * back that the first missed, and recover such a recording against a folder with no floor, which
 * links nothing. The link itself needs no saved floor: a floor that isn't saved only comes later
 * next time. An existing floor is never lowered, and archive drives aren't recorded into, so they
 * never reach back.
 *
 * <p>The floors live in their own file in the data folder, never in {@code settings.json}: that
 * file is shared ground with the Go remake, and a 0.1.x from before the settings loader kept keys it
 * doesn't know refuses a file that carries one. The Go remake keeps the same file in the same format
 * ({@code internal/fsm/adoption_floors.go}),
 * so once it takes over this data folder, the floors this app set still hold. The format is exactly
 * {@code {"version":1,"floors":{"<key>":<epoch millis>,...}}}, with {@link #key} naming each root.
 */
final class OrphanAdoptionFloors {

    private static final Logger log = LoggerFactory.getLogger(OrphanAdoptionFloors.class);

    static final String FILE_NAME = "orphan-adoption-floors.json";
    /** Marks the one-time release of the rows adopted before the floors as done (see the class notes). */
    static final String RELEASE_FILE_NAME = "orphan-adoption-release.json";
    private static final int VERSION = 1;

    /** Strict about trailing content, as Go's json.Unmarshal is, so both apps reject the same files. */
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Null when there's no data folder to keep floors in: then nothing is adopted. */
    private final Path file;
    private final Path releaseFile;

    OrphanAdoptionFloors(Path dataDir) {
        this.file = dataDir == null ? null : dataDir.resolve(FILE_NAME);
        this.releaseFile = dataDir == null ? null : dataDir.resolve(RELEASE_FILE_NAME);
    }

    /**
     * Whether the rows adopted before the floors were already released here, or can't be released at
     * all for want of a data folder. A marker that can't be checked counts as there.
     */
    boolean released() {
        return releaseFile == null || !Files.notExists(releaseFile);
    }

    /** Records the release as done, written as the floors are, so it never runs again here. */
    void markReleased(long releasedAtMs, int rows) throws IOException {
        if (releaseFile == null) {
            return;
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", VERSION);
        root.put("releasedAtMs", releasedAtMs);
        root.put("rows", rows);
        replace(releaseFile, root);
    }

    /** One root's floor. {@link #NONE} admits nothing. */
    record Floor(long fromMs, boolean set) {
        static final Floor NONE = new Floor(0L, false);

        /** Whether a file last modified at {@code modifiedMs} may be adopted or linked. */
        boolean admits(long modifiedMs) {
            return set && modifiedMs >= fromMs;
        }
    }

    /**
     * The floors one scan's imports are held to, as saved. Null floors admit nothing: a scan that
     * couldn't save its floors adopts nothing at all, the safe side in every case.
     */
    static final class Snapshot {
        private final Map<String, Long> floors;

        private Snapshot(Map<String, Long> floors) {
            this.floors = floors;
        }

        private static Snapshot none() {
            return new Snapshot(null);
        }

        /** The root's floor, which every adoption is held to. */
        Floor of(Path root) {
            return floorIn(floors, root);
        }

        /**
         * Whether these floors are on disk: false when they couldn't be saved, or there's no data
         * folder to keep them in.
         */
        boolean saved() {
            return floors != null;
        }
    }

    /**
     * One boot scan's floors, worked out before the journal is replayed so the link to a crashed
     * recording can use them, and saved after it by {@link #save}. Null floors admit nothing.
     */
    static final class Plan {
        private final Path file;
        private final Map<String, Long> floors;
        /** Keys of the roots this plan gives their first floor. */
        private final Set<String> added;
        /** Whether the file is rewritten even with nothing added: it was unreadable, or lost a floor. */
        private final boolean rewrite;
        private final String videoKey;
        /** The video folder's earlier floor for linking alone, when this plan gives it its first. */
        private final Long videoLinkFrom;
        /** The earliest floor the file held when this scan read it; null for no file or no floor. */
        private final Long earliestRead;
        /** Whether a video folder is set and this scan couldn't read it. */
        private final boolean videoUnreadable;

        private Plan(Path file, Map<String, Long> floors, Set<String> added, boolean rewrite,
                String videoKey, Long videoLinkFrom, Long earliestRead, boolean videoUnreadable) {
            this.file = file;
            this.floors = floors;
            this.added = added;
            this.rewrite = rewrite;
            this.videoKey = videoKey;
            this.videoLinkFrom = videoLinkFrom;
            this.earliestRead = earliestRead;
            this.videoUnreadable = videoUnreadable;
        }

        private static Plan none(boolean videoUnreadable) {
            return new Plan(null, null, Set.of(), false, null, null, null, videoUnreadable);
        }

        /**
         * Whether a video folder is set and this scan couldn't read it (a drive not mounted yet), so a
         * crashed recording whose file would be looked for there waits in the journal for a boot that
         * can read it. Decided by the same read as the folder's first floor (see the class notes).
         */
        boolean cantReadVideoDir() {
            return videoUnreadable;
        }

        /**
         * The earliest floor the floors file held when this scan read it, before any root was added
         * or dropped; empty when there was no file, it couldn't be read, or it held none.
         */
        OptionalLong earliestReadFloor() {
            return earliestRead == null ? OptionalLong.empty() : OptionalLong.of(earliestRead);
        }

        /**
         * The floor a link to a crashed recording is held to: the root's planned floor, saved or not,
         * or earlier for a video folder this plan gives its first floor while the journal holds a
         * pathless recording that started before it (see the class notes). Only the link may use it.
         */
        Floor linkFloorOf(Path root) {
            Floor planned = floorIn(floors, root);
            if (!planned.set() || videoLinkFrom == null) {
                return planned;
            }
            try {
                return key(root).equals(videoKey)
                        ? new Floor(Math.min(videoLinkFrom, planned.fromMs()), true)
                        : planned;
            } catch (RuntimeException | java.io.IOError e) {
                return planned;
            }
        }

        /** Whether the video folder's link reaches back before its first floor in this plan. */
        boolean linksBack() {
            return videoLinkFrom != null;
        }

        /**
         * Saves the planned floors and returns the ones this scan's imports are held to: none at all
         * when they can't be saved, since a floor that isn't saved might not be there next boot. With
         * {@code holdVideoFloor}, a first floor this plan gives the video folder is neither saved nor
         * used, so that folder adopts nothing this boot and the next one can still reach back (see the
         * class notes). Never throws.
         */
        Snapshot save(boolean holdVideoFloor) {
            if (floors == null) {
                return Snapshot.none();
            }
            try {
                Map<String, Long> saved = floors;
                Set<String> savedNew = added;
                if (holdVideoFloor && added.contains(videoKey)) {
                    saved = new TreeMap<>(floors);
                    saved.remove(videoKey);
                    savedNew = new HashSet<>(added);
                    savedNew.remove(videoKey);
                    log.info("Not saving the first orphan adoption floor of {} yet: a crashed recording"
                            + " whose file it may hold is still to be recovered", videoKey);
                }
                if (rewrite || !savedNew.isEmpty()) {
                    write(file, saved);
                }
                return new Snapshot(saved);
            } catch (Exception | java.io.IOError e) {
                log.warn("Could not save the orphan adoption floors {}; adopting no orphan recordings"
                        + " this time: {}", file, e.toString());
                return Snapshot.none();
            }
        }
    }

    /** {@code root}'s floor in {@code floors}; none for null floors or an unresolvable root. */
    private static Floor floorIn(Map<String, Long> floors, Path root) {
        if (floors == null || root == null) {
            return Floor.NONE;
        }
        try {
            Long from = floors.get(key(root));
            return from == null ? Floor.NONE : new Floor(from, true);
        } catch (RuntimeException | java.io.IOError e) {
            // An unresolvable root has no floor; a boot-time runner must never throw for one.
            return Floor.NONE;
        }
    }

    /**
     * Plans this scan's floors without saving any: gives every root that has no floor one, and drops
     * the floor of any other folder. A root's first floor is {@code startMs}, or 1 ms after the newest
     * file {@code newestHeldMs} reports in it when that is later; a root it reports as unreadable
     * (empty) gets none yet. {@code newestHeldMs} reads the video folder exactly once, floored or not,
     * and the plan keeps whether it could ({@link Plan#cantReadVideoDir}); another root is read only
     * when it has no floor. A video folder that gets its first floor here may link from {@code
     * videoLinkFromMs} when that is earlier than {@code startMs} ({@link Plan#linkFloorOf}); that is
     * never stored. Nothing is adopted until {@link Plan#save} has made the floors durable, so no file
     * is adopted against a floor a crash could lose. The roots must be every root the scan reads (the
     * Go remake prunes only in its full scan, never in its separate link pass). Never throws: whatever
     * goes wrong, the answer is to link and adopt nothing.
     */
    Plan plan(
            Path videoDir,
            Collection<Path> archives,
            long startMs,
            long videoLinkFromMs,
            Function<Path, OptionalLong> newestHeldMs) {
        OptionalLong videoHeld = held(videoDir, newestHeldMs);
        boolean videoUnreadable = videoDir != null && videoHeld.isEmpty();
        if (file == null) {
            log.warn("No data folder to keep orphan adoption floors in; adopting no orphan recordings");
            return Plan.none(videoUnreadable);
        }
        try {
            return planOrThrow(videoDir, videoHeld, archives, startMs, videoLinkFromMs, newestHeldMs);
        } catch (RuntimeException | java.io.IOError e) {
            log.warn("Could not prepare the orphan adoption floors {}; adopting no orphan recordings"
                    + " this time: {}", file, e.toString());
            return Plan.none(videoUnreadable);
        }
    }

    /** What {@code newestHeldMs} reports for {@code root}, or empty (unreadable) for none or a failure. */
    private static OptionalLong held(Path root, Function<Path, OptionalLong> newestHeldMs) {
        if (root == null) {
            return OptionalLong.empty();
        }
        try {
            return newestHeldMs.apply(root);
        } catch (RuntimeException | java.io.IOError e) {
            return OptionalLong.empty();
        }
    }

    private Plan planOrThrow(
            Path videoDir,
            OptionalLong videoHeld,
            Collection<Path> archives,
            long startMs,
            long videoLinkFromMs,
            Function<Path, OptionalLong> newestHeldMs) {
        List<Path> roots = new ArrayList<>();
        roots.add(videoDir);
        roots.addAll(archives);
        Map<String, Long> floors;
        boolean rewrite = false;
        try {
            floors = read(file);
        } catch (Exception e) {
            log.warn("Could not read the orphan adoption floors {}; every folder's floor starts again"
                    + " from now: {}", file, e.toString());
            floors = new TreeMap<>();
            rewrite = true;
        }
        Long earliestRead = floors.values().stream().min(Long::compare).orElse(null);
        Set<String> current = new HashSet<>();
        Set<String> added = new HashSet<>();
        String videoKey = videoDir == null ? null : key(videoDir);
        Long videoLinkFrom = null;
        for (int i = 0; i < roots.size(); i++) {
            Path root = roots.get(i);
            if (root == null) {
                continue;
            }
            String key = key(root);
            current.add(key);
            if (!floors.containsKey(key)) {
                OptionalLong newest = i == 0 ? videoHeld : newestHeldMs.apply(root);
                if (newest.isEmpty()) {
                    log.info("Can't read {} now; it gets its orphan adoption floor on a boot that can", root);
                    continue;
                }
                long from = firstFloor(startMs, newest.getAsLong());
                floors.put(key, from);
                added.add(key);
                log.info("Only recordings written to {} from {} on can be adopted as orphans", root, from);
                if (i == 0 && videoLinkFromMs < startMs) {
                    videoLinkFrom = videoLinkFromMs;
                    log.info("A crashed recording's file in {} can still be linked from {} on", root,
                            videoLinkFromMs);
                }
            }
        }
        for (Iterator<String> keys = floors.keySet().iterator(); keys.hasNext(); ) {
            String key = keys.next();
            if (!current.contains(key)) {
                keys.remove();
                rewrite = true;
                log.info("Dropped the orphan adoption floor of {}, no longer recorded or archived to", key);
            }
        }
        return new Plan(file, floors, added, rewrite, videoKey, videoLinkFrom, earliestRead,
                videoDir != null && videoHeld.isEmpty());
    }

    /**
     * A root's first floor: the scan's start, or 1 ms after the newest recorder-named file the root
     * holds when that is later, so nothing it already held passes even when this scan's clock is far
     * behind theirs.
     */
    private static long firstFloor(long startMs, long newestHeldMs) {
        return Math.max(startMs, newestHeldMs == Long.MAX_VALUE ? Long.MAX_VALUE : newestHeldMs + 1);
    }

    /**
     * Names a root in the file, the way the Go remake does too: made absolute and cleaned, backslash
     * separators, no trailing separator, lower case (Windows paths are case-insensitive). Lower-cased
     * per code point with {@link Character#toLowerCase(int)}, which matches Go's strings.ToLower;
     * {@link String#toLowerCase} would not, because of its final-sigma and dotted-I rules.
     */
    static String key(Path root) {
        String path = root.toAbsolutePath().normalize().toString().replace('/', '\\');
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '\\') {
            end--;
        }
        StringBuilder key = new StringBuilder(end);
        path.substring(0, end).codePoints().map(Character::toLowerCase).forEach(key::appendCodePoint);
        return key.toString();
    }

    /**
     * Returns no floors for a missing file, and throws for one that doesn't hold exactly an integer
     * version of 1 and a floors object of integers.
     */
    static Map<String, Long> read(Path file) throws IOException {
        byte[] content;
        try {
            content = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return new TreeMap<>();
        }
        JsonNode root = MAPPER.readTree(content);
        if (root == null || !root.isObject()) {
            throw new IOException("not a JSON object");
        }
        JsonNode version = root.get("version");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()
                || version.intValue() != VERSION) {
            throw new IOException("version " + version + ", want " + VERSION);
        }
        JsonNode stored = root.get("floors");
        if (stored == null || !stored.isObject()) {
            throw new IOException("floors is not an object");
        }
        Map<String, Long> floors = new TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : stored.properties()) {
            JsonNode from = entry.getValue();
            if (!from.isIntegralNumber() || !from.canConvertToLong()) {
                throw new IOException("floor for " + entry.getKey() + " is " + from);
            }
            floors.put(entry.getKey(), from.longValue());
        }
        return floors;
    }

    /**
     * Replaces the file through a temp file and a move, so a crash leaves the old floors or the new
     * ones, never a torn file.
     */
    static void write(Path file, Map<String, Long> floors) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", VERSION);
        ObjectNode stored = root.putObject("floors");
        new TreeMap<>(floors).forEach(stored::put);
        replace(file, root);
    }

    /** Writes {@code root} over {@code file} through {@code <file>.tmp} and a move. */
    private static void replace(Path file, ObjectNode root) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        try {
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // the move's failure is the one worth reporting
            }
            throw e;
        }
    }
}
