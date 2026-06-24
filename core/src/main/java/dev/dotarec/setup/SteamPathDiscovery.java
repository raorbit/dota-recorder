package dev.dotarec.setup;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Locates the Dota 2 install so the GSI cfg can be written into its game tree.
 *
 * <p>Discovery order (Windows): {@code HKCU\Software\Valve\Steam SteamPath} -&gt; {@code HKLM}
 * (WOW6432Node) {@code InstallPath} fallback -&gt; the default {@code %ProgramFiles(x86)%\Steam}. For
 * each Steam root, the libraries are the root's own {@code steamapps} plus every path declared in
 * {@code steamapps/libraryfolders.vdf}; each library is checked for {@code appmanifest_570.acf}
 * (570 = Dota 2) whose {@code installdir} resolves to {@code steamapps/common/<installdir>}. A
 * candidate is accepted only if it has the {@code game/dota/cfg} subtree the GSI cfg lives under, so
 * a stale manifest pointing at a deleted install is rejected.
 *
 * <p>The registry read is best-effort (it shells out to {@code reg query} and degrades to empty on
 * any failure); the filesystem walk is factored into {@link #findDotaUnderSteamRoot(Path)} and the
 * static parsers so it is unit-testable against a temp tree without a real Steam install.
 */
@Component
public class SteamPathDiscovery {

    private static final Logger log = LoggerFactory.getLogger(SteamPathDiscovery.class);

    /** Dota 2 Steam app id. */
    static final String DOTA_APP_ID = "570";

    private static final Pattern VDF_PATH = Pattern.compile("\"path\"\\s+\"([^\"]+)\"");
    private static final Pattern ACF_INSTALLDIR = Pattern.compile("\"installdir\"\\s+\"([^\"]+)\"");
    private static final Pattern REG_SZ_VALUE = Pattern.compile("REG_SZ\\s+(.+?)\\s*$");

    /**
     * Resolves the Dota 2 install directory (e.g. {@code .../steamapps/common/dota 2 beta}), or empty
     * if no Dota install with a {@code game/dota/cfg} tree is found under any Steam root.
     */
    public Optional<String> findDotaInstallDir() {
        for (Path steamRoot : steamRoots()) {
            Optional<Path> dota = findDotaUnderSteamRoot(steamRoot);
            if (dota.isPresent()) {
                log.info("Discovered Dota 2 install at {}", dota.get());
                return dota.map(Path::toString);
            }
        }
        log.info("No Dota 2 install discovered via Steam registry / libraryfolders.vdf");
        return Optional.empty();
    }

    /**
     * Candidate Steam roots in priority order (HKCU SteamPath, HKLM InstallPath, default install dir),
     * de-duplicated and filtered to existing directories. Package-private so a test can stub it; the
     * registry read is non-deterministic on a developer machine.
     */
    List<Path> steamRoots() {
        Set<Path> candidates = new LinkedHashSet<>();
        readRegistryValue("HKCU\\Software\\Valve\\Steam", "SteamPath").ifPresent(candidates::add);
        readRegistryValue("HKLM\\SOFTWARE\\WOW6432Node\\Valve\\Steam", "InstallPath")
                .ifPresent(candidates::add);
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        if (programFilesX86 != null && !programFilesX86.isBlank()) {
            candidates.add(Path.of(programFilesX86, "Steam"));
        }
        List<Path> existing = new ArrayList<>();
        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                existing.add(p);
            }
        }
        return existing;
    }

    /**
     * Finds the Dota install under one Steam root by checking every library (the root's own
     * {@code steamapps} plus each {@code libraryfolders.vdf} path) for {@code appmanifest_570.acf},
     * resolving {@code steamapps/common/<installdir>}, and accepting the first whose
     * {@code game/dota/cfg} subtree exists. Package-private + filesystem-only so it is unit-testable.
     */
    Optional<Path> findDotaUnderSteamRoot(Path steamRoot) {
        for (Path library : libraryRoots(steamRoot)) {
            Path manifest =
                    library.resolve("steamapps").resolve("appmanifest_" + DOTA_APP_ID + ".acf");
            if (!Files.isRegularFile(manifest)) {
                continue;
            }
            Optional<String> installDir = readInstallDir(manifest);
            if (installDir.isEmpty()) {
                continue;
            }
            Path dota = library.resolve("steamapps").resolve("common").resolve(installDir.get());
            if (Files.isDirectory(dota.resolve("game").resolve("dota").resolve("cfg"))) {
                return Optional.of(dota);
            }
        }
        return Optional.empty();
    }

    /** The Steam root itself plus every library path declared in its {@code libraryfolders.vdf}. */
    private List<Path> libraryRoots(Path steamRoot) {
        List<Path> roots = new ArrayList<>();
        roots.add(steamRoot);
        Path vdf = steamRoot.resolve("steamapps").resolve("libraryfolders.vdf");
        if (Files.isRegularFile(vdf)) {
            try {
                for (Path p : parseLibraryPaths(Files.readString(vdf, StandardCharsets.UTF_8))) {
                    if (!roots.contains(p)) {
                        roots.add(p);
                    }
                }
            } catch (IOException e) {
                log.debug("Could not read {}: {}", vdf, e.toString());
            }
        }
        return roots;
    }

    private Optional<String> readInstallDir(Path manifest) {
        try {
            return parseInstallDir(Files.readString(manifest, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.debug("Could not read {}: {}", manifest, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Extracts {@code "path"} values from a {@code libraryfolders.vdf}, un-escaping the doubled
     * backslashes Valve writes. Order-preserving and tolerant of both the legacy and nested vdf
     * shapes since it just scans for path entries.
     */
    static List<Path> parseLibraryPaths(String vdf) {
        List<Path> paths = new ArrayList<>();
        Matcher m = VDF_PATH.matcher(vdf);
        while (m.find()) {
            paths.add(Path.of(m.group(1).replace("\\\\", "\\")));
        }
        return paths;
    }

    /** Extracts the {@code "installdir"} value from an {@code appmanifest_*.acf}. */
    static Optional<String> parseInstallDir(String acf) {
        Matcher m = ACF_INSTALLDIR.matcher(acf);
        return m.find() ? Optional.of(m.group(1).replace("\\\\", "\\")) : Optional.empty();
    }

    /**
     * Best-effort {@code reg query} for a single REG_SZ value. Returns empty on any failure (non-Windows
     * OS, missing key, timeout) so discovery degrades to the default path / manual install rather than
     * throwing on the GSI-setup request thread.
     */
    private Optional<Path> readRegistryValue(String key, String valueName) {
        try {
            Process proc =
                    new ProcessBuilder("reg", "query", key, "/v", valueName)
                            .redirectErrorStream(true)
                            .start();
            String value = null;
            try (BufferedReader r =
                    new BufferedReader(
                            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains(valueName) && line.contains("REG_SZ")) {
                        Matcher m = REG_SZ_VALUE.matcher(line);
                        if (m.find()) {
                            value = m.group(1).trim();
                        }
                    }
                }
            }
            proc.waitFor();
            if (value != null && !value.isBlank()) {
                return Optional.of(Path.of(value));
            }
        } catch (IOException e) {
            log.debug("Registry read {} {} failed: {}", key, valueName, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Registry read {} {} interrupted", key, valueName);
        }
        return Optional.empty();
    }
}
