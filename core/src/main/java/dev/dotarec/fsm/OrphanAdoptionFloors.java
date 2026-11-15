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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 * A boot scan that no longer reads a folder drops its floor, so a folder chosen again, or an archive
 * drive added back, starts from a new floor: what it gained while it wasn't ours is never adopted. A
 * drive that is configured but unplugged is still a root and keeps its floor. Only a boot sees the
 * roots, so a folder left and chosen again within one run keeps its old floor.
 *
 * <p>The cost is lopsided on purpose. An orphan the floor turns away only stays on disk, where the
 * user can still find it; adopting the user's file hands it to deletion. So an upgraded install
 * never adopts what predates the upgrade, a newly chosen folder never adopts what it already held,
 * and when the floors can't be read they start again from now. A floor is set at the first boot scan
 * after a folder is chosen, not when it's chosen, so a crash orphan written there before that boot
 * stays on disk too. What's left: a personal OBS recording made into the same folder after its
 * floor, with OBS's default name, is still adopted.
 *
 * <p>One exception, for linking alone. When the video folder gets its first floor in a boot that
 * recovers an unfinished recording with no journaled path (the upgrade boot after a crash mid-match,
 * say), the link that claims that recording's file may reach back to the earliest such recording's
 * start, less the link window's grace: the journal is evidence this app was recording then, and a
 * floor at the scan would turn away the very file the journal is about to claim. The link is still
 * held to its recording's own window. The reach-back is never stored, and the folder's floor is the
 * scan's like any other, because the floor also gates the import of unclaimed orphans: stored, it
 * would admit every default-named file the user's own OBS wrote there from the crashed match on. So
 * a recording whose recovery fails on that boot can't link a file from before the floor on a later
 * one; the file stays on disk. An existing floor is never lowered, and archive drives aren't
 * recorded into, so they never reach back.
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
    private static final int VERSION = 1;

    /** Strict about trailing content, as Go's json.Unmarshal is, so both apps reject the same files. */
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Null when there's no data folder to keep floors in: then nothing is adopted. */
    private final Path file;

    OrphanAdoptionFloors(Path dataDir) {
        this.file = dataDir == null ? null : dataDir.resolve(FILE_NAME);
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
     * One scan's floors. Null floors admit nothing: a scan that couldn't save its floors adopts
     * nothing at all, the safe side in every case.
     */
    static final class Snapshot {
        private final Map<String, Long> floors;
        /** Earlier floors for linking alone, by root key, for roots that got their floor this scan. */
        private final Map<String, Long> linkFrom;

        private Snapshot(Map<String, Long> floors, Map<String, Long> linkFrom) {
            this.floors = floors;
            this.linkFrom = linkFrom;
        }

        private static Snapshot none() {
            return new Snapshot(null, Map.of());
        }

        /** The root's floor, which every adoption is held to. */
        Floor of(Path root) {
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
         * The floor a link to a crashed recording is held to: {@link #of}, or earlier for a video
         * folder that got its floor in this very scan while the journal held a pathless recording that
         * started before it (see the class notes). Only the link may use it.
         */
        Floor linkFloorOf(Path root) {
            Floor stored = of(root);
            if (!stored.set() || linkFrom.isEmpty()) {
                return stored;
            }
            try {
                Long from = linkFrom.get(key(root));
                return from == null ? stored : new Floor(Math.min(from, stored.fromMs()), true);
            } catch (RuntimeException | java.io.IOError e) {
                return stored;
            }
        }
    }

    /**
     * Gives every root that has no floor one, at {@code startMs}, drops the floor of any other folder,
     * and makes that durable before returning, so no file is adopted against a floor a crash could
     * lose. A video folder that gets its floor here may link from {@code videoLinkFromMs} when that is
     * earlier ({@link Snapshot#linkFloorOf}); that is never stored. The roots must be every root the
     * scan reads (the Go remake prunes only in its full scan, never in its separate link pass). Never
     * throws: whatever goes wrong, the answer is to adopt nothing.
     */
    Snapshot prepare(Path videoDir, Collection<Path> archives, long startMs, long videoLinkFromMs) {
        if (file == null) {
            log.warn("No data folder to keep orphan adoption floors in; adopting no orphan recordings");
            return Snapshot.none();
        }
        try {
            return prepareOrThrow(videoDir, archives, startMs, videoLinkFromMs);
        } catch (RuntimeException | java.io.IOError e) {
            log.warn("Could not prepare the orphan adoption floors {}; adopting no orphan recordings"
                    + " this time: {}", file, e.toString());
            return Snapshot.none();
        }
    }

    private Snapshot prepareOrThrow(
            Path videoDir, Collection<Path> archives, long startMs, long videoLinkFromMs) {
        List<Path> roots = new ArrayList<>();
        roots.add(videoDir);
        roots.addAll(archives);
        Map<String, Long> floors;
        boolean changed = false;
        try {
            floors = read(file);
        } catch (Exception e) {
            log.warn("Could not read the orphan adoption floors {}; every folder's floor starts again"
                    + " from now: {}", file, e.toString());
            floors = new TreeMap<>();
            changed = true;
        }
        Set<String> current = new HashSet<>();
        Map<String, Long> linkFrom = new HashMap<>();
        for (int i = 0; i < roots.size(); i++) {
            Path root = roots.get(i);
            if (root == null) {
                continue;
            }
            String key = key(root);
            current.add(key);
            if (!floors.containsKey(key)) {
                floors.put(key, startMs);
                changed = true;
                log.info("Only recordings written to {} from {} on can be adopted as orphans", root, startMs);
                if (i == 0 && videoLinkFromMs < startMs) {
                    linkFrom.put(key, videoLinkFromMs);
                    log.info("A crashed recording's file in {} can still be linked from {} on", root,
                            videoLinkFromMs);
                }
            }
        }
        for (Iterator<String> keys = floors.keySet().iterator(); keys.hasNext(); ) {
            String key = keys.next();
            if (!current.contains(key)) {
                keys.remove();
                changed = true;
                log.info("Dropped the orphan adoption floor of {}, no longer recorded or archived to", key);
            }
        }
        if (changed) {
            try {
                write(file, floors);
            } catch (Exception e) {
                log.warn("Could not save the orphan adoption floors {}; adopting no orphan recordings"
                        + " this time: {}", file, e.toString());
                return Snapshot.none();
            }
        }
        return new Snapshot(floors, linkFrom);
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
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
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
