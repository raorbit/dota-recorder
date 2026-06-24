package dev.dotarec.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the cfg writer mints+persists the token once, writes a verifiable cfg into the right
 * subtree pointing at {@code /gsi}, and that the manual fallback is byte-identical to the auto-install.
 */
class GsiCfgInstallerTest {

    private static SettingsStore settings(Path dir) {
        return new SettingsStore(new AppPaths(dir.toString(), dir.resolve("obs").toString()));
    }

    @Test
    void install_writesCfgWithGsiUriAndToken_intoTheGsiSubdir(
            @TempDir Path dir, @TempDir Path dota) throws IOException {
        SteamPathDiscovery discovery = mock(SteamPathDiscovery.class);
        when(discovery.findDotaInstallDir()).thenReturn(Optional.of(dota.toString()));
        SettingsStore settings = settings(dir);
        GsiCfgInstaller installer = new GsiCfgInstaller(discovery, settings);

        GsiCfgInstaller.InstallResult result = installer.install();

        assertThat(result.installed()).isTrue();
        assertThat(result.dotaDir()).isEqualTo(dota.toString());
        Path cfg = dota.resolve(GsiCfgInstaller.CFG_SUBDIR).resolve(GsiCfgInstaller.CFG_FILE_NAME);
        assertThat(Path.of(result.cfgPath())).isEqualTo(cfg);

        String body = Files.readString(cfg);
        assertThat(body).contains(GsiCfgInstaller.GSI_URI);
        assertThat(settings.get().gsiAuthToken).isNotBlank();
        assertThat(body).contains(settings.get().gsiAuthToken);
    }

    @Test
    void install_mintsTokenOnce_andReusesItAcrossInstalls(@TempDir Path dir, @TempDir Path dota) {
        SteamPathDiscovery discovery = mock(SteamPathDiscovery.class);
        when(discovery.findDotaInstallDir()).thenReturn(Optional.of(dota.toString()));
        SettingsStore settings = settings(dir);
        GsiCfgInstaller installer = new GsiCfgInstaller(discovery, settings);

        installer.install();
        String firstToken = settings.get().gsiAuthToken;
        installer.install();

        assertThat(settings.get().gsiAuthToken)
                .as("a second install must reuse the persisted token, not re-mint")
                .isEqualTo(firstToken);
    }

    @Test
    void install_returnsNotFound_whenDotaIsNotDiscovered(@TempDir Path dir) {
        SteamPathDiscovery discovery = mock(SteamPathDiscovery.class);
        when(discovery.findDotaInstallDir()).thenReturn(Optional.empty());
        GsiCfgInstaller installer = new GsiCfgInstaller(discovery, settings(dir));

        GsiCfgInstaller.InstallResult result = installer.install();

        assertThat(result.installed()).isFalse();
        assertThat(result.dotaDir()).isNull();
        assertThat(result.cfgPath()).isNull();
    }

    @Test
    void manualInstructions_matchTheAutoInstalledCfgByteForByte(
            @TempDir Path dir, @TempDir Path dota) throws IOException {
        SteamPathDiscovery discovery = mock(SteamPathDiscovery.class);
        when(discovery.findDotaInstallDir()).thenReturn(Optional.of(dota.toString()));
        SettingsStore settings = settings(dir);
        GsiCfgInstaller installer = new GsiCfgInstaller(discovery, settings);

        installer.install();
        String written =
                Files.readString(
                        dota.resolve(GsiCfgInstaller.CFG_SUBDIR)
                                .resolve(GsiCfgInstaller.CFG_FILE_NAME));
        GsiCfgInstaller.ManualInstructions manual = installer.manualInstructions();

        assertThat(manual.cfgFileName()).isEqualTo(GsiCfgInstaller.CFG_FILE_NAME);
        assertThat(manual.cfgBody()).isEqualTo(written);
        assertThat(manual.targetDir())
                .isEqualTo(dota.resolve(GsiCfgInstaller.CFG_SUBDIR).toString());
    }
}
