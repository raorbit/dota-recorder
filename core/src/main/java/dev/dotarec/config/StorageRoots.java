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
     * <p>Mirrors {@code RecordingArchiver.locationOf} normalization: each candidate is reduced to an
     * absolute, normalized path and matched as a case-insensitive string prefix terminated by a file
     * separator (Windows paths are case-insensitive, and the trailing separator keeps {@code C:\vid}
     * from matching a sibling {@code C:\video2\...}).
     *
     * @param file  the resolved file to check
     * @param roots the configured storage roots (any blank/unparseable root is skipped)
     * @return true when {@code file} is contained by at least one root
     */
    public static boolean isUnder(Path file, List<String> roots) {
        String fileStr;
        try {
            fileStr = file.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return false;
        }
        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            String dirStr;
            try {
                dirStr = Path.of(root).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
            } catch (RuntimeException e) {
                continue;
            }
            String dirPrefix = dirStr.endsWith(File.separator) ? dirStr : dirStr + File.separator;
            if (fileStr.startsWith(dirPrefix)) {
                return true;
            }
        }
        return false;
    }
}
