package dev.dotarec.obs.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import java.io.UncheckedIOException;
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
                // FilePath is written with forward slashes: OBS stores it in Qt QSettings INI form
                // where a single backslash is an escape char, so a raw Windows path would be mangled
                // on read ("bad output path", recording never starts). See ObsConfigWriter.writeProfile.
                .contains("FilePath=" + settings.get().videoDir.replace('\\', '/'));
        // The auto-detected encoder is written into the PROFILE but NOT persisted into settings: a blank
        // encoder stays the "auto" sentinel so the UI keeps showing Auto and a GPU swap re-probes on the
        // next launch (Codex C9 / ObsConfigWriter.resolveEncoder).
        assertThat(settings.get().encoder).isBlank();
    }

    @Test
    void writesUserVideoControlsIntoProfile(@TempDir Path dir) throws Exception {
        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        settings.update(
                s -> {
                    s.fps = 30;
                    s.quality = "Stream";
                    s.format = "mkv";
                    return s;
                });
        writer(paths, settings, "", "0").configure();

        Path ini = new ObsLayout(paths.obsDir()).profileIni();
        assertThat(Files.readString(ini))
                .contains("FPSCommon=30")
                .contains("RecQuality=Stream")
                .contains("RecFormat2=mkv")
                // FPSType stays the literal "Common FPS" mode.
                .contains("FPSType=0");
    }

    @Test
    void manualEncoderOverrideIsWrittenVerbatimAndNotProbed(@TempDir Path dir) throws Exception {
        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        // User explicitly picked x264 on an NVIDIA box: the manual (non-blank) choice must win over the
        // probe, be written verbatim into the profile, and stay persisted (the blank "auto" sentinel
        // must NOT override it). writer() supplies the nvidiaProbe, which would otherwise pick nvenc.
        settings.update(
                s -> {
                    s.encoder = "x264";
                    return s;
                });
        writer(paths, settings, "", "0").configure();

        Path ini = new ObsLayout(paths.obsDir()).profileIni();
        assertThat(Files.readString(ini)).contains("RecEncoder=x264");
        // The manual choice is preserved (not overwritten by the probe).
        assertThat(settings.get().encoder).isEqualTo("x264");
    }

    @Test
    void blankEncoderFallsBackToX264InProfile(@TempDir Path dir) throws Exception {
        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        // A no-GPU probe leaves the encoder blank; the profile must still write a usable x264 token
        // rather than an empty RecEncoder= that OBS would reject.
        EncoderProbe noGpu = new EncoderProbe(java.util.List::of);
        new ObsConfigWriter(paths, settings, noGpu, "", "0").configure();

        Path ini = new ObsLayout(paths.obsDir()).profileIni();
        assertThat(Files.readString(ini)).contains("RecEncoder=x264");
    }

    @Test
    void applyProfileRewritesIniWithoutOtherSideEffects(@TempDir Path dir) throws Exception {
        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        ObsConfigWriter writer = writer(paths, settings, "", "0");
        // Seed basic.ini once via configure(), then change a setting and re-apply ONLY the profile.
        writer.configure();
        settings.update(
                s -> {
                    s.quality = "Lossless";
                    return s;
                });
        writer.applyProfile();

        Path ini = new ObsLayout(paths.obsDir()).profileIni();
        assertThat(Files.readString(ini)).contains("RecQuality=Lossless");
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
    void versionBumpReCopiesAndPrunesStaleFiles(@TempDir Path dir) throws Exception {
        // v1 bundle ships a plugin that v2 will drop.
        Path v1 = dir.resolve("bundle-v1");
        Files.createDirectories(v1.resolve("bin/64bit"));
        Files.writeString(v1.resolve("bin/64bit/obs64.exe"), "MZ");
        Files.createDirectories(v1.resolve("obs-plugins/64bit"));
        Files.writeString(v1.resolve("obs-plugins/64bit/old-plugin.dll"), "OLD");

        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        writer(paths, settings, v1.toString(), "32.1.2").configure();

        Path stalePlugin =
                paths.obsDir().resolve("obs-plugins").resolve("64bit").resolve("old-plugin.dll");
        assertThat(stalePlugin).exists();

        // A user/runtime file under the config tree must survive the upgrade prune.
        Path userScene =
                paths.obsDir().resolve("config").resolve("obs-studio").resolve("user-scene.json");
        Files.createDirectories(userScene.getParent());
        Files.writeString(userScene, "KEEP");

        // v2 bundle no longer ships old-plugin.dll; it ships a different plugin instead.
        Path v2 = dir.resolve("bundle-v2");
        Files.createDirectories(v2.resolve("bin/64bit"));
        Files.writeString(v2.resolve("bin/64bit/obs64.exe"), "MZ2");
        Files.createDirectories(v2.resolve("obs-plugins/64bit"));
        Files.writeString(v2.resolve("obs-plugins/64bit/new-plugin.dll"), "NEW");

        writer(paths, settings, v2.toString(), "33.0.0").configure();

        ObsLayout layout = new ObsLayout(paths.obsDir());
        // Orphan from v1 is gone; the new tree + version stamp are in place.
        assertThat(stalePlugin).doesNotExist();
        assertThat(paths.obsDir().resolve("obs-plugins").resolve("64bit").resolve("new-plugin.dll"))
                .exists();
        assertThat(Files.readString(layout.versionStamp()).trim()).isEqualTo("33.0.0");
        // The config tree was preserved across the prune.
        assertThat(Files.readString(userScene)).isEqualTo("KEEP");
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
    void profileWriteFailureLeavesPreExistingIniIntact(@TempDir Path dir) throws Exception {
        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        ObsConfigWriter writer = writer(paths, settings, "", "0");
        // Seed a good basic.ini once.
        writer.configure();
        Path ini = new ObsLayout(paths.obsDir()).profileIni();
        String original = Files.readString(ini);
        assertThat(original).contains("Mode=Simple");

        // Force the next atomic write to fail: occupy the sibling ".tmp" path with a DIRECTORY so the
        // temp-file write throws before the target is ever touched. The pre-existing basic.ini must be
        // left byte-for-byte intact (never truncated), and the failure must surface as an exception.
        Path tmp = ini.resolveSibling(ini.getFileName() + ".tmp");
        Files.createDirectory(tmp);
        settings.update(
                s -> {
                    s.quality = "Lossless";
                    return s;
                });
        assertThatThrownBy(writer::applyProfile).isInstanceOf(UncheckedIOException.class);

        // Atomicity: the old complete file survives; the failed write did not truncate or partially
        // apply the new "Lossless" quality.
        assertThat(Files.readString(ini)).isEqualTo(original);
    }

    @Test
    void videoDirContainingATokenIsWrittenVerbatimNotMangled(@TempDir Path dir) throws Exception {
        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        // A videoDir that literally contains another template token (@FPS@). With an ordered chain of
        // String.replace (@REC_PATH@ substituted first, @FPS@ after) the inserted "@FPS@" in the path
        // would be re-scanned and corrupted into the fps value. The single-pass substitution must write
        // the path verbatim.
        settings.update(
                s -> {
                    s.videoDir = "D:\\clips\\@FPS@\\dota";
                    s.fps = 30;
                    return s;
                });
        writer(paths, settings, "", "0").configure();

        Path ini = new ObsLayout(paths.obsDir()).profileIni();
        String content = Files.readString(ini);
        // The @FPS@ inside the path is preserved (forward-slashed like every FilePath), NOT rewritten
        // to "30"; the real FPSCommon token still resolved to 30.
        assertThat(content).contains("FilePath=D:/clips/@FPS@/dota");
        assertThat(content).contains("FPSCommon=30");
    }

    @Test
    void parseResolutionFallsBackOnGarbage() {
        assertThat(ObsConfigWriter.parseResolution(null)).containsExactly(1920, 1080);
        assertThat(ObsConfigWriter.parseResolution("garbage")).containsExactly(1920, 1080);
        assertThat(ObsConfigWriter.parseResolution("1280x720")).containsExactly(1280, 720);
    }
}
