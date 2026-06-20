package dev.dotarec.obs.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObsConfigWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private AppPaths paths(Path dir) {
        return new AppPaths(dir.resolve("data").toString(), dir.resolve("obs").toString());
    }

    private EncoderProbe nvidiaProbe() {
        return new EncoderProbe(() -> List.of("NVIDIA GeForce RTX 4070"));
    }

    private ObsConfigWriter writer(AppPaths paths, SettingsStore settings, String sourceDir, String version) {
        return new ObsConfigWriter(paths, settings, nvidiaProbe(), sourceDir, version);
    }

    @Test
    void generatesAndPersistsCredentialsIdempotently(@TempDir Path dir) {
        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        ObsConfigWriter writer = writer(paths, settings, "", "0");

        writer.configure();
        String pw = settings.get().obsPassword;
        assertThat(pw).isNotBlank();
        assertThat(pw.length()).isGreaterThanOrEqualTo(20);
        assertThat(settings.get().obsPort).isEqualTo(4466);

        // Second run must NOT rotate the password.
        writer.configure();
        assertThat(settings.get().obsPassword).isEqualTo(pw);

        // A fresh store reading the same settings.json sees the persisted password.
        assertThat(new SettingsStore(paths).get().obsPassword).isEqualTo(pw);
    }

    @Test
    void writesWebsocketModuleConfig(@TempDir Path dir) throws Exception {
        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        writer(paths, settings, "", "0").configure();

        Path cfg = new ObsLayout(paths.obsDir()).websocketConfig();
        assertThat(cfg).exists();
        JsonNode json = mapper.readTree(cfg.toFile());
        assertThat(json.get("server_enabled").asBoolean()).isTrue();
        assertThat(json.get("auth_required").asBoolean()).isTrue();
        assertThat(json.get("server_port").asInt()).isEqualTo(4466);
        assertThat(json.get("server_password").asText()).isEqualTo(settings.get().obsPassword);
    }

    @Test
    void writesSimpleOutputProfileWithProbedEncoder(@TempDir Path dir) throws Exception {
        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        settings.get().resolution = "2560x1440";
        writer(paths, settings, "", "0").configure();

        Path ini = new ObsLayout(paths.obsDir()).profileIni();
        assertThat(ini).exists();
        assertThat(Files.readString(ini))
                .contains("Mode=Simple")
                .contains("RecFormat2=hybrid_mp4")
                .contains("RecEncoder=nvenc")
                .contains("BaseCX=2560")
                .contains("BaseCY=1440")
                .contains("FilePath=" + settings.get().videoDir);
        // Probed encoder token is persisted so the UI can reflect it.
        assertThat(settings.get().encoder).isEqualTo("nvenc");
    }

    @Test
    void firstRunCopyMaterializesObsAndIsIdempotent(@TempDir Path dir) throws Exception {
        // Fake bundled OBS source tree (just enough for the obs64.exe presence gate).
        Path source = dir.resolve("bundle");
        Files.createDirectories(source.resolve("bin/64bit"));
        Files.writeString(source.resolve("bin/64bit/obs64.exe"), "MZ");
        Files.createDirectories(source.resolve("data"));
        Files.writeString(source.resolve("data/locale.txt"), "en-US");

        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        ObsConfigWriter writer = writer(paths, settings, source.toString(), "32.1.2");
        writer.configure();

        ObsLayout layout = new ObsLayout(paths.obsDir());
        assertThat(layout.obs64()).exists();
        assertThat(paths.obsDir().resolve("data").resolve("locale.txt")).exists();
        assertThat(layout.portableMarker()).exists();
        assertThat(Files.readString(layout.versionStamp()).trim()).isEqualTo("32.1.2");

        // Touch a copied file; a second configure() with the same version must NOT re-copy over it.
        Path copied = paths.obsDir().resolve("data").resolve("locale.txt");
        Files.writeString(copied, "TOUCHED");
        writer.configure();
        assertThat(Files.readString(copied)).isEqualTo("TOUCHED");
    }

    @Test
    void skipsCopyButStillWritesConfigWhenNoSource(@TempDir Path dir) {
        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        writer(paths, settings, "", "0").configure();

        ObsLayout layout = new ObsLayout(paths.obsDir());
        assertThat(layout.obs64()).doesNotExist();
        assertThat(layout.websocketConfig()).exists();
        assertThat(layout.profileIni()).exists();
    }

    @Test
    void parseResolutionFallsBackOnGarbage() {
        assertThat(ObsConfigWriter.parseResolution(null)).containsExactly(1920, 1080);
        assertThat(ObsConfigWriter.parseResolution("garbage")).containsExactly(1920, 1080);
        assertThat(ObsConfigWriter.parseResolution("1280x720")).containsExactly(1280, 720);
    }
}
