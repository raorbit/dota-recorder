package dev.dotarec.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the filesystem walk of {@link SteamPathDiscovery} against a fake Steam tree under a
 * {@code @TempDir} (the registry read is stubbed out by calling {@link
 * SteamPathDiscovery#findDotaUnderSteamRoot} directly). Covers single/second library, a renamed
 * install dir, the missing-cfg rejection, and a malformed {@code libraryfolders.vdf} degrading
 * gracefully rather than throwing.
 */
class SteamPathDiscoveryTest {

    private final SteamPathDiscovery discovery = new SteamPathDiscovery();

    /** Writes a valid Dota install (manifest + the {@code game/dota/cfg} tree) under {@code library}. */
    private static Path makeDotaLibrary(Path library, String installDir) throws IOException {
        Path steamapps = library.resolve("steamapps");
        Files.createDirectories(steamapps);
        Files.writeString(
                steamapps.resolve("appmanifest_570.acf"),
                "\"AppState\"\n{\n\t\"appid\"\t\"570\"\n\t\"installdir\"\t\"" + installDir + "\"\n}\n");
        Path dota = steamapps.resolve("common").resolve(installDir);
        Files.createDirectories(dota.resolve("game").resolve("dota").resolve("cfg"));
        return dota;
    }

    @Test
    void singleLibrary_findsDotaInTheSteamRootItself(@TempDir Path steamRoot) throws IOException {
        Path dota = makeDotaLibrary(steamRoot, "dota 2 beta");
        assertThat(discovery.findDotaUnderSteamRoot(steamRoot)).contains(dota);
    }

    @Test
    void secondLibrary_findsDotaViaLibraryfoldersVdf(
            @TempDir Path steamRoot, @TempDir Path otherLib) throws IOException {
        // Steam root has no Dota; a second library declared in libraryfolders.vdf does.
        Files.createDirectories(steamRoot.resolve("steamapps"));
        Path dota = makeDotaLibrary(otherLib, "dota 2 beta");
        Files.writeString(
                steamRoot.resolve("steamapps").resolve("libraryfolders.vdf"),
                "\"libraryfolders\"\n{\n\t\"0\"\n\t{\n\t\t\"path\"\t\""
                        + otherLib.toString().replace("\\", "\\\\")
                        + "\"\n\t}\n}\n");
        assertThat(discovery.findDotaUnderSteamRoot(steamRoot)).contains(dota);
    }

    @Test
    void renamedInstallDir_isResolvedFromTheManifest(@TempDir Path steamRoot) throws IOException {
        Path dota = makeDotaLibrary(steamRoot, "dota 2 test");
        assertThat(discovery.findDotaUnderSteamRoot(steamRoot)).contains(dota);
    }

    @Test
    void manifestPresentButNoCfgTree_isNotAccepted(@TempDir Path steamRoot) throws IOException {
        Path steamapps = steamRoot.resolve("steamapps");
        Files.createDirectories(steamapps.resolve("common").resolve("dota 2 beta"));
        Files.writeString(
                steamapps.resolve("appmanifest_570.acf"),
                "\"AppState\"\n{\n\t\"installdir\"\t\"dota 2 beta\"\n}\n");
        // No game/dota/cfg subtree -> a stale manifest pointing at a deleted install, rejected.
        assertThat(discovery.findDotaUnderSteamRoot(steamRoot)).isEmpty();
    }

    @Test
    void malformedLibraryfoldersVdf_stillFindsDotaInTheRoot(@TempDir Path steamRoot)
            throws IOException {
        Path dota = makeDotaLibrary(steamRoot, "dota 2 beta");
        Files.writeString(
                steamRoot.resolve("steamapps").resolve("libraryfolders.vdf"), "not valid vdf {{{");
        assertThat(discovery.findDotaUnderSteamRoot(steamRoot)).contains(dota);
    }

    @Test
    void noManifestAnywhere_returnsEmpty(@TempDir Path steamRoot) throws IOException {
        Files.createDirectories(steamRoot.resolve("steamapps"));
        assertThat(discovery.findDotaUnderSteamRoot(steamRoot)).isEmpty();
    }

    @Test
    void parseLibraryPaths_unescapesBackslashesAndPreservesOrder() {
        String vdf =
                "\"libraryfolders\"\n{\n"
                        + "\t\"0\"\n\t{\n\t\t\"path\"\t\"C:\\\\Program Files (x86)\\\\Steam\"\n\t}\n"
                        + "\t\"1\"\n\t{\n\t\t\"path\"\t\"D:\\\\SteamLibrary\"\n\t}\n}\n";
        List<Path> paths = SteamPathDiscovery.parseLibraryPaths(vdf);
        assertThat(paths)
                .containsExactly(
                        Path.of("C:\\Program Files (x86)\\Steam"), Path.of("D:\\SteamLibrary"));
    }

    @Test
    void parseInstallDir_extractsTheValue() {
        Optional<String> dir =
                SteamPathDiscovery.parseInstallDir(
                        "\"AppState\"\n{\n\t\"installdir\"\t\"dota 2 beta\"\n}\n");
        assertThat(dir).contains("dota 2 beta");
    }
}
