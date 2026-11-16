package dev.dotarec.fsm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What {@link CrashRecoveryRunner} last found comparing an archived copy with the original it was made
 * from, kept across boots in {@value #FILE_NAME} in the data folder. A comparison that settles
 * anything reads both files end to end, gigabytes each, under the maintenance lock that holds back
 * OBS's launch and every delete. When a boot could do nothing with what it found (the original was
 * whole to delete but another program held it, or the two differ and neither can be told for the
 * damaged one; a pair that can't be read fails fast, so it isn't kept here), the next boot would read them again and find the same. So such a pair is looked at
 * again only once either file changes (its size or modification time), or {@link #RECHECK_AFTER_MS}
 * after it was compared.
 *
 * <p>A remembered verdict never deletes or re-links anything: the boot that acts on a pair always
 * reads it again first. It only keeps a pair as it is, or keeps a row whose file another row holds a
 * whole copy of, both of which change nothing on disk. A file that can't be read or written here only
 * costs the reads it would have saved.
 */
final class ArchivedCopyChecks {

    private static final Logger log = LoggerFactory.getLogger(ArchivedCopyChecks.class);

    static final String FILE_NAME = "archived-copy-checks.json";
    private static final int VERSION = 1;

    /** How long a pair whose files haven't changed is left as it is before it is compared again. */
    static final long RECHECK_AFTER_MS = 7L * 24 * 60 * 60_000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One pair as both files were when compared: paths, sizes and modification times. */
    record Pair(String copy, long copySize, long copyModifiedMs,
                String original, long originalSize, long originalModifiedMs) {

        /** The pair as both files are now, or null when either can't be read. */
        static Pair of(Path copy, Path original) {
            try {
                return new Pair(
                        copy.toString(), Files.size(copy), Files.getLastModifiedTime(copy).toMillis(),
                        original.toString(), Files.size(original),
                        Files.getLastModifiedTime(original).toMillis());
            } catch (Exception e) {
                return null;
            }
        }
    }

    private record Check(String verdict, long atMs) {}

    /** Null when there's no data folder: then nothing is remembered. */
    private final Path file;
    private final long nowMs;
    private final Map<Pair, Check> read = new HashMap<>();
    /** The pairs this boot compared or left as an earlier boot found them: all {@link #save} keeps. */
    private final Map<Pair, Check> seen = new HashMap<>();

    ArchivedCopyChecks(Path dataDir, long nowMs) {
        this.file = dataDir == null ? null : dataDir.resolve(FILE_NAME);
        this.nowMs = nowMs;
        if (file != null) {
            load();
        }
    }

    /**
     * The verdict an earlier boot found for {@code copy} against {@code original}, while neither file
     * has changed and it is recent, else null.
     */
    String recent(Path copy, Path original) {
        Pair pair = Pair.of(copy, original);
        Check check = pair == null ? null : read.get(pair);
        if (check == null || check.atMs() > nowMs || nowMs - check.atMs() >= RECHECK_AFTER_MS) {
            return null;
        }
        seen.put(pair, check);
        return check.verdict();
    }

    /** Remembers what this boot found for {@code copy} against {@code original}. */
    void found(Path copy, Path original, String verdict) {
        Pair pair = Pair.of(copy, original);
        if (pair != null) {
            seen.put(pair, new Check(verdict, nowMs));
        }
    }

    /** Writes the pairs this boot saw, dropping the rest, or removes the file when there are none. */
    void save() {
        if (file == null) {
            return;
        }
        try {
            if (seen.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            ObjectNode root = MAPPER.createObjectNode();
            root.put("version", VERSION);
            ArrayNode pairs = root.putArray("pairs");
            seen.forEach((pair, check) -> {
                ObjectNode entry = pairs.addObject();
                entry.put("copy", pair.copy());
                entry.put("copySize", pair.copySize());
                entry.put("copyModifiedMs", pair.copyModifiedMs());
                entry.put("original", pair.original());
                entry.put("originalSize", pair.originalSize());
                entry.put("originalModifiedMs", pair.originalModifiedMs());
                entry.put("verdict", check.verdict());
                entry.put("atMs", check.atMs());
            });
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.createDirectories(file.getParent());
            Files.write(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Could not save {}: {}", file, e.toString());
        }
    }

    private void load() {
        try {
            JsonNode root = MAPPER.readTree(Files.readAllBytes(file));
            if (root == null || root.path("version").asInt() != VERSION || !root.path("pairs").isArray()) {
                return;
            }
            for (JsonNode entry : root.get("pairs")) {
                JsonNode verdict = entry.get("verdict");
                if (verdict == null || !verdict.isTextual()) {
                    continue;
                }
                read.put(
                        new Pair(
                                entry.path("copy").asText(), entry.path("copySize").asLong(-1),
                                entry.path("copyModifiedMs").asLong(-1), entry.path("original").asText(),
                                entry.path("originalSize").asLong(-1), entry.path("originalModifiedMs").asLong(-1)),
                        new Check(verdict.asText(), entry.path("atMs").asLong(Long.MAX_VALUE)));
            }
        } catch (NoSuchFileException e) {
            // nothing remembered yet
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read {}; comparing every pair again: {}", file, e.toString());
        }
    }
}
