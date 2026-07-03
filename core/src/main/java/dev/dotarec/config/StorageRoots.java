package dev.dotarec.config;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The configured storage roots and the containment guard that decides whether a stored file lives
 * under one of them. Shared by the bridge stream endpoints (path-traversal guard) and the retention
 * sweeper (never unlink a misrooted file), so the two enforcers can never diverge on the trust
 * boundary.
 *
 * <p>The allow-list is the active {@code videoDir}, every archive drive's path, and every historical
 * {@code videoDir} the user has since moved off. The historical dirs are READ+DELETE roots only (rows
 * recorded before a videoDir change keep absolute paths under the old folder), so their files stay
 * streamable and a delete/sweep still unlinks them — recording/archiver never target them.
 */
public final class StorageRoots {

    private StorageRoots() {
    }

    /**
     * The canonical form of a path for containment and distinctness checks: an absolute, {@code .}/{@code
     * ..}-collapsed, lowercased string. Defined ONCE here so the stream guard, the retention
     * archiver/sweeper, and the
     * settings distinctness check can never canonicalize the same path two different ways. Lowercasing
     * uses {@link Locale#ROOT} so the fold is deterministic across the host's default locale (Turkish
     * {@code I} would otherwise fold differently).
     */
    public static String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    /** {@link #normalize(Path)} for a raw path string. */
    public static String normalize(String path) {
        return normalize(Path.of(path));
    }

    /**
     * A {@link #normalize normalized} directory turned into a containment prefix by appending a trailing
     * {@link File#separator}, so {@code c:\vid} matches a child {@code c:\vid\a.mp4} but not a sibling
     * {@code c:\video2\...}.
     */
    public static String prefix(String normalizedDir) {
        return normalizedDir.endsWith(File.separator) ? normalizedDir : normalizedDir + File.separator;
    }

    /**
     * The configured storage roots: the active {@code videoDir}, every archive drive's path, and every
     * historical {@code videoDir}. Defined ONCE here so the match/clip controllers and the retention
     * sweeper can never enforce divergent allow-lists.
     */
    public static List<String> of(SettingsStore.Settings s) {
        List<String> roots = new ArrayList<>();
        roots.add(s.videoDir);
        if (s.storageLocations != null) {
            for (SettingsStore.StorageLocation loc : s.storageLocations) {
                if (loc != null) {
                    roots.add(loc.path());
                }
            }
        }
        if (s.previousVideoDirs != null) {
            roots.addAll(s.previousVideoDirs);
        }
        return roots;
    }

    /**
     * Whether {@code file} is contained by at least one of {@code roots} — a real file must resolve to
     * a path that lives under one of the configured storage roots. A path outside every root (a
     * tampered DB row, a {@code ..} escape, a stale/misrooted file) is rejected.
     *
     * <p>Uses {@link #normalize normalize}/{@link #prefix prefix}, the same canonical form the retention
     * archiver and the settings distinctness check share: each candidate is reduced to an absolute,
     * normalized path and matched as a case-insensitive string prefix terminated by a file separator
     * (Windows paths are case-insensitive, and the trailing separator keeps {@code C:\vid} from matching
     * a sibling {@code C:\video2\...}).
     *
     * @param file  the resolved file to check
     * @param roots the configured storage roots (any blank/unparseable root is skipped)
     * @return true when {@code file} is contained by at least one root
     */
    public static boolean isUnder(Path file, List<String> roots) {
        String fileStr;
        try {
            fileStr = normalize(file);
        } catch (RuntimeException e) {
            return false;
        }
        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            String dirStr;
            try {
                dirStr = normalize(root);
            } catch (RuntimeException e) {
                continue;
            }
            if (fileStr.startsWith(prefix(dirStr))) {
                return true;
            }
        }
        return false;
    }
}
