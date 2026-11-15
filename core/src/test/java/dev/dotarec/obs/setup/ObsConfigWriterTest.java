package dev.dotarec.obs.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

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
    void freshInstall_marksFirstRunDone_soTheAutoConfigWizardNeverOpens(@TempDir Path dir) throws Exception {
        // Regression: a fresh OBS opened its Auto-Configuration Wizard, which disables outputs while
        // open; tray-hidden, it silently swallowed every StartRecord of the first live match.
        AppPaths paths = paths(dir);
        writer(paths, new SettingsStore(paths), "", "0").configure();

        Path userIni = new ObsLayout(paths.obsDir()).userIni();
        assertThat(Files.readString(userIni)).isEqualTo("[General]\nFirstRun=true\n");
    }

    @Test
    void unreadableUserIni_stillWritesTheProfileAndCompletes(@TempDir Path dir) throws Exception {
        // Regression: the FirstRun edit ran BEFORE writeProfile and threw, so a briefly locked
        // user.ini (e.g. an AV scan on first run) aborted configure() before basic.ini was written and
        // ObsConfigBootstrap never marked readiness -- a cosmetic write blocked all recording.
        // Occupying user.ini's path with a DIRECTORY makes its read fail reliably on Windows and Linux.
        AppPaths paths = paths(dir);
        ObsLayout layout = new ObsLayout(paths.obsDir());
        Files.createDirectories(layout.userIni());

        writer(paths, new SettingsStore(paths), "", "0").configure();

        assertThat(Files.readString(layout.profileIni())).contains("Mode=Simple");
        assertThat(layout.websocketConfig()).exists();
        assertThat(layout.userIni()).isDirectory();
    }

    @Test
    void unwritableUserIni_stillWritesTheProfileAndCompletes(@TempDir Path dir) throws Exception {
        // The write-side twin: writeAtomically throws UncheckedIOException (not IOException) when its
        // sibling .tmp cannot be written -- occupied here by a DIRECTORY -- and that is swallowed too.
        AppPaths paths = paths(dir);
        ObsLayout layout = new ObsLayout(paths.obsDir());
        Path tmp = layout.userIni().resolveSibling(layout.userIni().getFileName() + ".tmp");
        Files.createDirectories(tmp);

        writer(paths, new SettingsStore(paths), "", "0").configure();

        assertThat(Files.readString(layout.profileIni())).contains("Mode=Simple");
        assertThat(layout.userIni()).doesNotExist();
    }

    @Test
    void firstRunDone_mergesIntoOBSsOwnUserIni() {
        // The shape OBS itself writes: UTF-8 BOM, LF endings, [General] first, no FirstRun yet.
        String obs = "\uFEFF[General]\nPre19Defaults=false\nConfirmOnExit=true\n\n[BasicWindow]\nPreviewEnabled=true\n";
        String merged = ObsConfigWriter.withFirstRunDone(obs);
        assertThat(merged).isEqualTo(
                "\uFEFF[General]\nFirstRun=true\nPre19Defaults=false\nConfirmOnExit=true\n\n[BasicWindow]\nPreviewEnabled=true\n");
        // Idempotent, and a false flag is flipped in place.
        assertThat(ObsConfigWriter.withFirstRunDone(merged)).isEqualTo(merged);
        assertThat(ObsConfigWriter.withFirstRunDone("[General]\r\nFirstRun=false\r\nX=1\r\n"))
                .isEqualTo("[General]\r\nFirstRun=true\r\nX=1\r\n");
        // A FirstRun key in another section is not the frontend's flag.
        assertThat(ObsConfigWriter.withFirstRunDone("[Other]\nFirstRun=false\n"))
                .isEqualTo("[General]\nFirstRun=true\n\n[Other]\nFirstRun=false\n");
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
                // At the default Stream quality OBS records with the STREAMING encoder, so the probed
                // token must be written there too or OBS's own default encoder runs instead.
                .contains("StreamEncoder=nvenc")
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
        assertThat(Files.readString(ini))
                .contains("RecEncoder=x264")
                // ...and as the streaming encoder, which OBS uses at the default Stream quality.
                .contains("StreamEncoder=x264");
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
    void fallBackToX264_switchesAnAutoPickedHardwareEncoder_forTheRestOfTheProcess(@TempDir Path dir)
            throws Exception {
        // An Intel-only PC: OBS offers QSV on any Intel GPU, so an iGPU whose driver can't open it
        // fails every record start. Once the FSM sees that, the profile must name x264 for both
        // encoders: the default Stream quality records with the streaming one.
        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        AtomicInteger probes = new AtomicInteger();
        EncoderProbe intel = new EncoderProbe(() -> {
            probes.incrementAndGet();
            return List.of("Intel(R) UHD Graphics 620");
        });
        ObsConfigWriter writer = new ObsConfigWriter(paths, settings, intel, "", "0");
        writer.configure();
        Path ini = new ObsLayout(paths.obsDir()).profileIni();
        assertThat(Files.readString(ini)).contains("RecEncoder=qsv").contains("StreamEncoder=qsv");

        List<ILoggingEvent> logged = logsOf(() -> assertThat(writer.fallBackToX264()).isTrue());

        assertThat(Files.readString(ini)).contains("RecEncoder=x264").contains("StreamEncoder=x264");
        assertThat(logged)
                .as("the switch is logged where a slow-PC complaint would be looked into")
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("qsv to x264"));
        // Auto stays auto: nothing is persisted, so the UI keeps showing Auto.
        assertThat(settings.get().encoder).isBlank();
        // A settings save later in the session re-writes the profile with x264 too, unprobed.
        writer.applyProfile();
        assertThat(Files.readString(ini)).contains("StreamEncoder=x264");
        assertThat(probes).hasValue(1);
        // One-way: nothing is left to switch.
        assertThat(writer.fallBackToX264()).isFalse();

        // The next launch (a new process) probes again, so a fixed driver gets QSV back.
        new ObsConfigWriter(paths, settings, intel, "", "0").configure();
        assertThat(Files.readString(ini)).contains("StreamEncoder=qsv");
    }

    @Test
    void fallBackToX264_keepsAnEncoderPickedInSettings_andSaysSo(@TempDir Path dir) throws Exception {
        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        settings.update(
                s -> {
                    s.encoder = "amd";
                    return s;
                });
        ObsConfigWriter writer = writer(paths, settings, "", "0");
        writer.configure();

        List<ILoggingEvent> logged = logsOf(() -> assertThat(writer.fallBackToX264()).isFalse());

        Path ini = new ObsLayout(paths.obsDir()).profileIni();
        assertThat(Files.readString(ini)).contains("RecEncoder=amd").contains("StreamEncoder=amd");
        assertThat(settings.get().encoder).isEqualTo("amd");
        assertThat(logged)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains("amd encoder picked in Settings"));
    }

    @Test
    void fallBackToX264_hasNothingToSwitch_whenAlreadyOnX264(@TempDir Path dir) {
        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        ObsConfigWriter noGpu =
                new ObsConfigWriter(paths, settings, new EncoderProbe(List::of), "", "0");
        noGpu.configure();
        assertThat(noGpu.fallBackToX264()).as("auto already resolved to x264").isFalse();

        settings.update(
                s -> {
                    s.encoder = "x264";
                    return s;
                });
        ObsConfigWriter picked = writer(paths, settings, "", "0");
        picked.configure();
        List<ILoggingEvent> logged = logsOf(() -> assertThat(picked.fallBackToX264()).isFalse());
        assertThat(logged)
                .as("no advice to pick x264 when x264 is the pick")
                .noneMatch(e -> e.getLevel() == Level.WARN);
    }

    @Test
    void fallBackToX264_thatCantWriteTheProfile_triesAgainNextTime(@TempDir Path dir) throws Exception {
        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        ObsConfigWriter writer = writer(paths, settings, "", "0");
        writer.configure();
        Path ini = new ObsLayout(paths.obsDir()).profileIni();
        assertThat(Files.readString(ini)).contains("StreamEncoder=nvenc");
        // Fail the write as profileWriteFailureLeavesPreExistingIniIntact does: a DIRECTORY on .tmp.
        Path tmp = ini.resolveSibling(ini.getFileName() + ".tmp");
        Files.createDirectory(tmp);

        assertThat(writer.fallBackToX264()).isFalse();
        assertThat(Files.readString(ini)).contains("StreamEncoder=nvenc");

        // With x264 left cached, this call would find nothing to switch while basic.ini still names
        // the encoder OBS can't open.
        Files.delete(tmp);
        assertThat(writer.fallBackToX264()).isTrue();
        assertThat(Files.readString(ini)).contains("StreamEncoder=x264");
    }

    @Test
    void fallBackToX264_leavesTheEncoderAlone_whileTheVideoFolderIsMissing(@TempDir Path dir)
            throws Exception {
        // The video drive is unplugged. OBS turns every start down before it opens any encoder, a
        // relaunched one too, so the starts fail just as a broken encoder's do. Switched anyway, the
        // rest of the process would CPU-encode every match once the drive is back.
        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        Path videos = dir.resolve("unplugged").resolve("DotaVODs");
        settings.update(
                s -> {
                    s.videoDir = videos.toString();
                    return s;
                });
        ObsConfigWriter writer = writer(paths, settings, "", "0");
        writer.configure();
        Path ini = new ObsLayout(paths.obsDir()).profileIni();

        List<ILoggingEvent> logged = logsOf(() -> assertThat(writer.fallBackToX264()).isFalse());

        assertThat(Files.readString(ini)).contains("RecEncoder=nvenc").contains("StreamEncoder=nvenc");
        assertThat(logged)
                .as("the log names what stops recording, not the GPU driver")
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains(videos.toString())
                        .contains("doesn't exist")
                        .doesNotContain("driver"));

        // With the drive back, starts that still fail are the encoder's.
        Files.createDirectories(videos);
        assertThat(writer.fallBackToX264()).isTrue();
        assertThat(Files.readString(ini)).contains("StreamEncoder=x264");
    }

    @Test
    void fallBackToX264_leavesTheEncoderAlone_whileTheVideoDriveHasUnder50MbFree(@TempDir Path dir)
            throws Exception {
        // OBS turns a start down with under 50 MB free on the folder's drive, before it opens any
        // encoder. Exactly 50 MB is enough for it, and then a failing start is the encoder's.
        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        ObsConfigWriter writer = writer(paths, settings, "", "0");
        long[] free = {ObsConfigWriter.OBS_MIN_FREE_BYTES - 1};
        ReflectionTestUtils.setField(
                writer, "usableSpace", (ObsConfigWriter.UsableSpace) folder -> free[0]);
        writer.configure();
        Path ini = new ObsLayout(paths.obsDir()).profileIni();

        List<ILoggingEvent> logged = logsOf(() -> assertThat(writer.fallBackToX264()).isFalse());

        assertThat(Files.readString(ini)).contains("StreamEncoder=nvenc");
        assertThat(logged)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains(paths.videoDir().toString())
                        .contains("49 MB free"));

        free[0] = ObsConfigWriter.OBS_MIN_FREE_BYTES;
        assertThat(writer.fallBackToX264()).isTrue();
        assertThat(Files.readString(ini)).contains("StreamEncoder=x264");
    }

    @Test
    void fallBackToX264_blamesAMissingFolder_beforeAnEncoderPickedInSettings(@TempDir Path dir) {
        // Advice to change the encoder would send the user after the wrong thing.
        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        settings.update(
                s -> {
                    s.encoder = "amd";
                    s.videoDir = dir.resolve("gone").toString();
                    return s;
                });
        ObsConfigWriter writer = writer(paths, settings, "", "0");
        writer.configure();

        List<ILoggingEvent> logged = logsOf(() -> assertThat(writer.fallBackToX264()).isFalse());

        assertThat(logged)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains("doesn't exist")
                        .doesNotContain("picked in Settings"));
    }

    @Test
    void fallBackToX264_stillSwitches_whenTheFreeSpaceCantBeRead(@TempDir Path dir) throws Exception {
        // A check that can't be made blames nothing, so a broken encoder still gets x264.
        AppPaths paths = paths(dir);
        SettingsStore settings = new SettingsStore(paths);
        ObsConfigWriter writer = writer(paths, settings, "", "0");
        ReflectionTestUtils.setField(writer, "usableSpace", (ObsConfigWriter.UsableSpace) folder -> {
            throw new IOException("the volume went away");
        });
        writer.configure();

        assertThat(writer.fallBackToX264()).isTrue();
        assertThat(Files.readString(new ObsLayout(paths.obsDir()).profileIni()))
                .contains("StreamEncoder=x264");
    }

    /** What {@link ObsConfigWriter} logs while {@code action} runs. */
    private static List<ILoggingEvent> logsOf(Runnable action) {
        Logger writerLog = (Logger) LoggerFactory.getLogger(ObsConfigWriter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        writerLog.addAppender(appender);
        try {
            action.run();
        } finally {
            writerLog.detachAppender(appender);
            appender.stop();
        }
        return appender.list;
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
