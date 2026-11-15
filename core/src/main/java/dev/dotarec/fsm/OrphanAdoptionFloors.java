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
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
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
 * <p>The floors live in their own file in the data folder, never in {@code settings.json}: that
 * file is shared ground with the Go remake, and this app's settings loader rejects a key it doesn't
 * know. The Go remake keeps the same file in the same format ({@code internal/fsm/adoption_floors.go}),
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

        private Snapshot(Map<String, Long> floors) {
            this.floors = floors;
        }

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
    }

    /**
     * Gives every root that has no floor one at {@code startMs}, drops the floor of any folder not in
     * {@code roots}, and makes that durable before returning, so no file is adopted against a floor a
     * crash could lose. {@code roots} must be every root the scan reads (the Go remake prunes only in
     * its full scan, never in its separate link pass). Never throws: whatever goes wrong, the answer
     * is to adopt nothing.
     */
    Snapshot prepare(Collection<Path> roots, long startMs) {
        if (file == null) {
            log.warn("No data folder to keep orphan adoption floors in; adopting no orphan recordings");
            return new Snapshot(null);
        }
        try {
            return prepareOrThrow(roots, startMs);
        } catch (RuntimeException | java.io.IOError e) {
            log.warn("Could not prepare the orphan adoption floors {}; adopting no orphan recordings"
                    + " this time: {}", file, e.toString());
            return new Snapshot(null);
        }
    }

    private Snapshot prepareOrThrow(Collection<Path> roots, long startMs) {
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
        for (Path root : roots) {
            if (root == null) {
                continue;
            }
            String key = key(root);
            current.add(key);
            if (!floors.containsKey(key)) {
                floors.put(key, startMs);
                changed = true;
                log.info("Only recordings written to {} from now on can be adopted as orphans", root);
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
                return new Snapshot(null);
            }
        }
        return new Snapshot(floors);
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
