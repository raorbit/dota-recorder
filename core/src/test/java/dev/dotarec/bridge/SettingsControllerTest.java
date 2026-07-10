package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.bridge.SettingsController.SettingsPatch;
import dev.dotarec.bridge.SettingsController.SettingsView;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.config.SettingsStore.AudioSource;
import dev.dotarec.config.SettingsStore.StorageLocation;
import dev.dotarec.config.StorageRoots;
import dev.dotarec.obs.ObsController;
import dev.dotarec.obs.setup.ObsConfigWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link SettingsController}. The OBS connection (host/port/password) is app-managed
 * and must not appear on the GET/PUT surface; the user-facing fields are the only writable surface.
 * Wired with a real {@link SettingsStore} against a temp {@link AppPaths} so no Spring context is
 * needed.
 */
class SettingsControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Path tmp;
    private SettingsStore store;
    private ObsConfigWriter obsConfigWriter;
    private SettingsController controller;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        this.tmp = tmp;
        AppPaths paths =
                new AppPaths(tmp.resolve("data").toString(), tmp.resolve("obs").toString());
        store = new SettingsStore(paths);
        // OBS not connected in unit tests: reconcileAudioOnDemand is a no-op, so the PUT never 500s.
        ObsController obsController = mock(ObsController.class);
        when(obsController.ensureConnected()).thenReturn(false);
        // applyProfile() re-writes basic.ini after a PUT; mock it so the happy-path test never touches
        // the OBS dir on disk. Individual tests can stub it to throw to exercise the failure surfacing.
        obsConfigWriter = mock(ObsConfigWriter.class);
        controller = new SettingsController(store, obsController, obsConfigWriter);
    }

    /**
     * An absolute temp-rooted path for a PUT to accept: the storage-path validation probes the real
     * filesystem (absolute, creatable, writable), so accepted paths must live under the test's temp
     * dir — a fictional {@code D:/...} would be rejected (or worse, created on a real drive).
     */
    private String dir(String name) {
        return tmp.resolve(name).toString();
    }

    @Test
    void getSettings_jsonHasNoObsFields() throws Exception {
        SettingsView view = controller.getSettings();

        JsonNode json = mapper.valueToTree(view);
        assertThat(json.has("obsHost")).isFalse();
        assertThat(json.has("obsPort")).isFalse();
        assertThat(json.has("obsPasswordSet")).isFalse();
        // The user-facing fields are still present.
        assertThat(json.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "resolution",
                        "encoder",
                        "retentionCapGb",
                        "videoDir",
                        "accountId",
                        "audioSources",
                        "fps",
                        "quality",
                        "format",
                        "storageLocations",
                        "autoClipOnRampage",
                        "clipPaddingSeconds",
                        "recordDemoMatches");
    }

    @Test
    void putSettings_roundTripsUserFacingFields() {
        String clips = dir("clips");
        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                "1280x720", "x264", 80, clips, 96828122L, null, null, null,
                                null, null, null, null, null, null));

        assertThat(updated.resolution()).isEqualTo("1280x720");
        assertThat(updated.encoder()).isEqualTo("x264");
        assertThat(updated.retentionCapGb()).isEqualTo(80);
        assertThat(updated.videoDir()).isEqualTo(clips);
        assertThat(updated.accountId()).isEqualTo(96828122L);

        // Persisted to the store, not just echoed.
        assertThat(store.get().resolution).isEqualTo("1280x720");
        assertThat(store.get().encoder).isEqualTo("x264");
        assertThat(store.get().retentionCapGb).isEqualTo(80);
        assertThat(store.get().videoDir).isEqualTo(clips);
        assertThat(store.get().accountId).isEqualTo(96828122L);
    }

    @Test
    void putSettings_roundTripsVideoControls() {
        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, null, null, null, null, 30, "Stream", "mkv", null,
                                null, null, null));

        assertThat(updated.fps()).isEqualTo(30);
        assertThat(updated.quality()).isEqualTo("Stream");
        assertThat(updated.format()).isEqualTo("mkv");
        assertThat(store.get().fps).isEqualTo(30);
        assertThat(store.get().quality).isEqualTo("Stream");
        assertThat(store.get().format).isEqualTo("mkv");
    }

    @Test
    void putSettings_surfacesProfileWriteFailure() {
        // A failed basic.ini re-write must NOT be swallowed at debug and return 200: that would leave a
        // stale/broken OBS profile silently, so OBS never emits OUTPUT_STARTED for the rest of the
        // session. Surface it as a 500 instead. The settings are still persisted (the write happened
        // before applyProfile), so a retry or the next boot picks them up.
        doThrow(new RuntimeException("disk full")).when(obsConfigWriter).applyProfile();

        assertThatThrownBy(
                        () ->
                                controller.putSettings(
                                        new SettingsPatch(
                                                "1280x720", null, null, null, null, null, null, null,
                                                null, null, null, null, null, null)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));

        // The settings were persisted before the profile re-write was attempted.
        assertThat(store.get().resolution).isEqualTo("1280x720");
    }

    @Test
    void putSettings_rejectsInvalidFps() {
        // A garbage fps would write a broken OBS [Video] FPSCommon -> abort every match. Reject it.
        assertThatThrownBy(
                        () ->
                                controller.putSettings(
                                        new SettingsPatch(
                                                null, null, null, null, null, null, null, 144, null,
                                                null, null, null, null, null)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        // The store is left untouched (default 60fps), not partially mutated.
        assertThat(store.get().fps).isEqualTo(60);
    }

    @Test
    void putSettings_rejectsInvalidQuality() {
        assertThatThrownBy(
                        () ->
                                controller.putSettings(
                                        new SettingsPatch(
                                                null, null, null, null, null, null, null, null,
                                                "garbage", null, null, null, null, null)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(store.get().quality).isEqualTo("HQ");
    }

    @Test
    void putSettings_rejectsInvalidFormat() {
        // RecFormat2=avi is exactly the kind of bad value that broke OUTPUT_STARTED on this branch.
        assertThatThrownBy(
                        () ->
                                controller.putSettings(
                                        new SettingsPatch(
                                                null, null, null, null, null, null, null, null, null,
                                                "avi", null, null, null, null)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(store.get().format).isEqualTo("hybrid_mp4");
    }

    @Test
    void putSettings_partialFpsPatch_leavesQualityFormatResolutionUnchanged() {
        // Seed non-default video controls, then PUT only fps.
        store.update(
                s -> {
                    s.quality = "Lossless";
                    s.format = "mov";
                    s.resolution = "2560x1440";
                    return s;
                });

        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, null, null, null, null, 30, null, null, null,
                                null, null, null));

        assertThat(updated.fps()).isEqualTo(30);
        // The omitted fields are left exactly as they were.
        assertThat(store.get().quality).isEqualTo("Lossless");
        assertThat(store.get().format).isEqualTo("mov");
        assertThat(store.get().resolution).isEqualTo("2560x1440");
    }

    @Test
    void putSettings_preservesAppManagedObsPassword() {
        // Seed an app-generated, non-default password through the store.
        store.update(
                s -> {
                    s.obsPassword = "abc1234567890def";
                    s.obsPort = 4466;
                    return s;
                });

        // PUT an unrelated, user-facing field only.
        controller.putSettings(
                new SettingsPatch(
                        "1280x720", null, null, null, null, null, null, null, null, null, null,
                        null, null, null));

        // Regression: the carry-forward must not wipe the OBS secret/port back to defaults.
        assertThat(store.get().obsPassword).isEqualTo("abc1234567890def");
        assertThat(store.get().obsPort).isEqualTo(4466);
        assertThat(store.get().resolution).isEqualTo("1280x720");
    }

    @Test
    void putSettings_clearsAccountIdWhenFlagged() {
        store.update(s -> { s.accountId = 96828122L; return s; });

        // A blanked Account ID field sends clearAccountId=true with no accountId value.
        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, null, null, true, null, null, null, null, null,
                                null, null, null));

        assertThat(updated.accountId()).isNull();
        assertThat(store.get().accountId).isNull();
    }

    @Test
    void putSettings_rejectsAccountIdLargerThan32Bit() {
        // A pasted 64-bit SteamID (76561198057093850) is not a Dota account id; Number() in the UI
        // corrupts it to an imprecise float. Reject it server-side too rather than persist a wrong id
        // the tagger keys the player's own events off of.
        assertThatThrownBy(
                        () ->
                                controller.putSettings(
                                        new SettingsPatch(
                                                null, null, null, null, 76561198057093850L, null, null,
                                                null, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        // Nothing persisted: the account id stays unset.
        assertThat(store.get().accountId).isNull();
    }

    @Test
    void putSettings_rejectsNonPositiveAccountId() {
        // 0 (and zero-padded inputs) slip past the UI guard but is not a real account id; reject it.
        assertThatThrownBy(
                        () ->
                                controller.putSettings(
                                        new SettingsPatch(
                                                null, null, null, null, 0L, null, null, null, null,
                                                null, null, null, null, null)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(store.get().accountId).isNull();
    }

    @Test
    void putSettings_nullAccountIdLeavesItUnchanged() {
        store.update(s -> { s.accountId = 96828122L; return s; });

        // Without the clear flag, a null accountId means "leave unchanged".
        controller.putSettings(
                new SettingsPatch(
                        "1280x720", null, null, null, null, null, null, null, null, null, null,
                        null, null, null));

        assertThat(store.get().accountId).isEqualTo(96828122L);
    }

    @Test
    void putSettings_rejectsUnknownAudioSourceKind() {
        SettingsPatch patch =
                new SettingsPatch(
                        null, null, null, null, null, null,
                        List.of(new AudioSource("x", "bogus", "t", "L", 100, false)),
                        null, null, null, null, null, null, null);
        assertThatThrownBy(() -> controller.putSettings(patch))
                .isInstanceOf(ResponseStatusException.class);
        // Rejected before persist: still the fresh-install seed (Dota + the two built-in rows), not
        // replaced by the invalid list.
        assertThat(store.get().audioSources).hasSize(3);
    }

    @Test
    void putSettings_rejectsOutOfRangeAudioVolume() {
        SettingsPatch patch =
                new SettingsPatch(
                        null, null, null, null, null, null,
                        List.of(new AudioSource("x", "output", "default", "L", 150, false)),
                        null, null, null, null, null, null, null);
        assertThatThrownBy(() -> controller.putSettings(patch))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(store.get().audioSources).hasSize(3);
    }

    @Test
    void getSettings_audioSourcesAlwaysNonEmptyOnFreshInstall() {
        // The fresh-install seed: a Dota application capture (on) so the game's audio records out of
        // the box, plus the two always-present built-in mixer rows (microphone + desktop audio), both
        // off so neither the mic nor the desktop mix leaks into a recording uninvited.
        SettingsView view = controller.getSettings();
        assertThat(view.audioSources()).hasSize(3);

        AudioSource game =
                view.audioSources().stream()
                        .filter(s -> "application".equals(s.kind()))
                        .findFirst()
                        .orElseThrow();
        assertThat(game.target()).isEqualTo("::dota2.exe");
        assertThat(game.volume()).isEqualTo(100);
        assertThat(game.muted()).isFalse();

        assertThat(view.audioSources())
                .filteredOn(s -> SettingsStore.BUILTIN_MICROPHONE_ID.equals(s.id()))
                .singleElement()
                .satisfies(s -> assertThat(s.muted()).isTrue());
        assertThat(view.audioSources())
                .filteredOn(s -> SettingsStore.BUILTIN_DESKTOP_ID.equals(s.id()))
                .singleElement()
                .satisfies(s -> assertThat(s.muted()).isTrue());
    }

    @Test
    void putSettings_audioSources_fullListReplace() {
        List<AudioSource> sources =
                List.of(
                        new AudioSource("id-1", "output", "default", "Desktop", 100, false),
                        new AudioSource("id-2", "application", "::Discord.exe", "Discord", 80, true));

        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, null, null, null, sources, null, null, null, null,
                                null, null, null));

        assertThat(updated.audioSources()).hasSize(2);
        assertThat(store.get().audioSources).hasSize(2);
        assertThat(store.get().audioSources.get(1).target()).isEqualTo("::Discord.exe");
        assertThat(store.get().audioSources.get(1).muted()).isTrue();
    }

    @Test
    void putSettings_nullAudioSources_leavesListUnchanged() {
        List<AudioSource> sources =
                List.of(new AudioSource("id-1", "input", "mic", "Mic", 50, false));
        controller.putSettings(
                new SettingsPatch(
                        null, null, null, null, null, null, sources, null, null, null, null, null,
                        null, null));

        // A later PUT with null audioSources must not touch the stored list.
        controller.putSettings(
                new SettingsPatch(
                        "1280x720", null, null, null, null, null, null, null, null, null, null,
                        null, null, null));

        assertThat(store.get().audioSources).hasSize(1);
        assertThat(store.get().audioSources.get(0).id()).isEqualTo("id-1");
    }

    @Test
    void putSettings_emptyAudioSources_clearsList() {
        // An explicit empty array clears all sources (distinct from null = unchanged).
        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, null, null, null, List.of(), null, null, null, null,
                                null, null, null));

        assertThat(updated.audioSources()).isEmpty();
        assertThat(store.get().audioSources).isEmpty();
    }

    // ---- storage locations (multi-drive) -----------------------------------

    @Test
    void getSettings_storageLocationsEmptyOnFreshInstall() {
        // Single-drive default: no archive drives until the user adds one.
        assertThat(controller.getSettings().storageLocations()).isEmpty();
    }

    @Test
    void putSettings_storageLocations_fullListReplace() {
        String archiveA = dir("dota-archive-a");
        String archiveB = dir("dota-archive-b");
        List<StorageLocation> locs =
                List.of(
                        new StorageLocation("a", archiveA, 2000),
                        new StorageLocation("b", archiveB, 4000));

        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, null, null, null, null, null, null, null, locs,
                                null, null, null));

        assertThat(updated.storageLocations()).hasSize(2);
        assertThat(store.get().storageLocations).hasSize(2);
        assertThat(store.get().storageLocations.get(1).path()).isEqualTo(archiveB);
        assertThat(store.get().storageLocations.get(1).capGb()).isEqualTo(4000);
    }

    @Test
    void putSettings_nullStorageLocations_leavesListUnchanged() {
        String archive = dir("archive");
        controller.putSettings(
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null,
                        List.of(new StorageLocation("a", archive, 500)), null, null, null));

        // A later PUT with null storageLocations must not touch the stored list.
        controller.putSettings(
                new SettingsPatch(
                        "1280x720", null, null, null, null, null, null, null, null, null, null,
                        null, null, null));

        assertThat(store.get().storageLocations).hasSize(1);
        assertThat(store.get().storageLocations.get(0).path()).isEqualTo(archive);
    }

    @Test
    void putSettings_emptyStorageLocations_clearsList() {
        controller.putSettings(
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null,
                        List.of(new StorageLocation("a", dir("archive"), 500)), null, null, null));

        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, null, null, null, null, null, null, null, List.of(),
                                null, null, null));

        assertThat(updated.storageLocations()).isEmpty();
        assertThat(store.get().storageLocations).isEmpty();
    }

    @Test
    void putSettings_rejectsBlankStorageLocationPath() {
        SettingsPatch patch =
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null,
                        List.of(new StorageLocation("a", "  ", 500)), null, null, null);
        assertThatThrownBy(() -> controller.putSettings(patch))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(store.get().storageLocations).isEmpty();
    }

    @Test
    void putSettings_rejectsNonPositiveStorageCap() {
        SettingsPatch patch =
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null,
                        List.of(new StorageLocation("a", "E:/archive", 0)), null, null, null);
        assertThatThrownBy(() -> controller.putSettings(patch))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void putSettings_rejectsDuplicateStoragePaths() {
        SettingsPatch patch =
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null,
                        List.of(
                                new StorageLocation("a", "E:/archive", 500),
                                new StorageLocation("b", "E:/archive", 800)),
                        null, null, null);
        assertThatThrownBy(() -> controller.putSettings(patch))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void putSettings_rejectsArchivePathMatchingActiveRecordingDir() {
        // An archive drive pointed at the active recording folder would move a file onto itself.
        SettingsPatch patch =
                new SettingsPatch(
                        null, null, null, "D:/clips", null, null, null, null, null, null,
                        List.of(new StorageLocation("a", "D:/clips", 500)), null, null, null);
        assertThatThrownBy(() -> controller.putSettings(patch))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void putSettings_rejectsArchiveNestedUnderActiveRecordingDir() {
        // A nested pair (D:\rec + D:\rec\archive) is rejected: bytes under the inner dir would be
        // double-counted toward both locations and the archiver would keep attributing the same file
        // to two drives (recurring no-op self-moves). Containment, not just exact duplication, is bad.
        SettingsPatch patch =
                new SettingsPatch(
                        null, null, null, "D:/rec", null, null, null, null, null, null,
                        List.of(new StorageLocation("a", "D:/rec/archive", 500)), null, null, null);
        assertThatThrownBy(() -> controller.putSettings(patch))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        // Nothing persisted: the store keeps its fresh-install empty archive list.
        assertThat(store.get().storageLocations).isEmpty();
    }

    @Test
    void putSettings_rejectsArchiveNestedUnderAnotherArchive() {
        // The same containment check applies BETWEEN two archive drives, not just against the active
        // dir: E:\a contains E:\a\b, so the pair is rejected.
        SettingsPatch patch =
                new SettingsPatch(
                        null, null, null, "D:/clips", null, null, null, null, null, null,
                        List.of(
                                new StorageLocation("a", "E:/archive", 500),
                                new StorageLocation("b", "E:/archive/inner", 800)),
                        null, null, null);
        assertThatThrownBy(() -> controller.putSettings(patch))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void putSettings_rejectsRelativeArchivePathThatCanonicalizesOntoActiveDir() {
        // A relative archive path (".") must be canonicalized the SAME way the byte-attribution code
        // does (toAbsolutePath().normalize()) BEFORE the distinctness check, or it could slip past here
        // yet still resolve onto the active recording dir at move time (a self-move). With the active
        // dir set to the JVM working dir, "." canonicalizes onto it and must be rejected.
        String activeDir = System.getProperty("user.dir");
        SettingsPatch patch =
                new SettingsPatch(
                        null, null, null, activeDir, null, null, null, null, null, null,
                        List.of(new StorageLocation("a", ".", 500)), null, null, null);
        assertThatThrownBy(() -> controller.putSettings(patch))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void putSettings_rejectsNonPositiveRetentionCap() {
        // A cleared "Max storage" field arrives as 0; persisting retentionCapGb=0 would starve the
        // sweeper's budget. Reject it (400), mirroring the per-archive cap check.
        assertThatThrownBy(
                        () ->
                                controller.putSettings(
                                        new SettingsPatch(
                                                null, null, 0, null, null, null, null, null, null,
                                                null, null, null, null, null)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        // The store keeps its default 50 GiB cap, not a partially-applied 0.
        assertThat(store.get().retentionCapGb).isEqualTo(50);
    }

    @Test
    void putSettings_changingVideoDir_retainsTheOldDirAsHistorical() {
        // Seed a non-default active dir, then move it. The outgoing dir must be retained so recordings
        // written under it stay streamable/deletable (their rows keep absolute paths under the old dir).
        String clipsOld = dir("clips-old");
        String clipsNew = dir("clips-new");
        controller.putSettings(
                new SettingsPatch(
                        null, null, null, clipsOld, null, null, null, null, null, null, null,
                        null, null, null));

        controller.putSettings(
                new SettingsPatch(
                        null, null, null, clipsNew, null, null, null, null, null, null, null,
                        null, null, null));

        assertThat(store.get().videoDir).isEqualTo(clipsNew);
        assertThat(store.get().previousVideoDirs).contains(clipsOld);
    }

    @Test
    void putSettings_videoDirUnchanged_doesNotRecordItAsHistorical() {
        // Seed the active dir directly (not via a PUT, which would itself record the default as
        // historical) so the list starts empty.
        String clips = dir("clips");
        store.update(s -> { s.videoDir = clips; return s; });

        // Re-PUT the SAME videoDir: it is neither a move nor a leak, so nothing is retained.
        controller.putSettings(
                new SettingsPatch(
                        null, null, null, clips, null, null, null, null, null, null, null,
                        null, null, null));

        assertThat(store.get().videoDir).isEqualTo(clips);
        assertThat(store.get().previousVideoDirs).isEmpty();
    }

    @Test
    void putSettings_videoDirOnlyPatch_rejectedWhenItNestsInStoredArchive() {
        // Seed a stored archive location, then a videoDir-only PUT that nests INSIDE it. The overlap
        // rule must run against the ALREADY-STORED archive list even though this PUT omits it.
        String clips = dir("clips");
        String archive = dir("archive");
        controller.putSettings(
                new SettingsPatch(
                        null, null, null, clips, null, null, null, null, null, null,
                        List.of(new StorageLocation("a", archive, 500)), null, null, null));

        assertThatThrownBy(
                        () ->
                                controller.putSettings(
                                        new SettingsPatch(
                                                null, null, null, archive + "/inner", null, null, null,
                                                null, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        // Rejected before persist: the active dir is unchanged.
        assertThat(store.get().videoDir).isEqualTo(clips);
    }

    @Test
    void putSettings_videoDirOnlyPatch_acceptedWhenDistinctFromStoredArchive() {
        // A stored archive on a different folder: a videoDir-only PUT that does NOT overlap it is fine.
        controller.putSettings(
                new SettingsPatch(
                        null, null, null, dir("clips"), null, null, null, null, null, null,
                        List.of(new StorageLocation("a", dir("archive"), 500)), null, null, null));

        String newClips = dir("new-clips");
        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, newClips, null, null, null, null, null, null,
                                null, null, null, null));

        assertThat(updated.videoDir()).isEqualTo(newClips);
        // The stored archive list is untouched by a videoDir-only PUT.
        assertThat(store.get().storageLocations).hasSize(1);
    }

    @Test
    void putSettings_rejectsBlankVideoDir() {
        // A cleared Output folder field arrives as a blank string. Persisting it would leave OBS,
        // thumbnails, and the archiver disagreeing about where recordings live, so reject it (400)
        // rather than store a blank that each subsystem interprets differently.
        assertThatThrownBy(
                        () ->
                                controller.putSettings(
                                        new SettingsPatch(
                                                null, null, null, "   ", null, null, null, null, null,
                                                null, null, null, null, null)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        // The store keeps its default (non-blank) videoDir, not a partially-applied blank.
        assertThat(store.get().videoDir).isNotBlank();
    }

    // ---- clip settings (auto-clip + padding) -------------------------------

    @Test
    void putSettings_roundTripsClipFields() {
        // autoClipOnRampage true with an in-range padding.
        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, null, null, null, null, null, null, null, null,
                                true, 12, null));

        assertThat(updated.autoClipOnRampage()).isTrue();
        assertThat(updated.clipPaddingSeconds()).isEqualTo(12);
        // Persisted to the store, not just echoed.
        assertThat(store.get().autoClipOnRampage).isTrue();
        assertThat(store.get().clipPaddingSeconds).isEqualTo(12);

        // ...and flipping the flag back off round-trips too.
        SettingsView off =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, null, null, null, null, null, null, null, null,
                                false, 45, null));

        assertThat(off.autoClipOnRampage()).isFalse();
        assertThat(off.clipPaddingSeconds()).isEqualTo(45);
        assertThat(store.get().autoClipOnRampage).isFalse();
        assertThat(store.get().clipPaddingSeconds).isEqualTo(45);
    }

    @Test
    void putSettings_roundTripsRecordDemoMatches() {
        // Off by default on a fresh install: Hero Demo sessions are skipped until opted in.
        assertThat(controller.getSettings().recordDemoMatches()).isFalse();

        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, null, null, null, null, null, null, null, null,
                                null, null, true));

        assertThat(updated.recordDemoMatches()).isTrue();
        // Persisted to the store, not just echoed.
        assertThat(store.get().recordDemoMatches).isTrue();

        // A later PUT that omits the field (null) leaves it unchanged.
        controller.putSettings(
                new SettingsPatch(
                        "1280x720", null, null, null, null, null, null, null, null, null, null,
                        null, null, null));
        assertThat(store.get().recordDemoMatches).isTrue();

        // ...and flipping it back off round-trips too.
        SettingsView off =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, null, null, null, null, null, null, null, null,
                                null, null, false));
        assertThat(off.recordDemoMatches()).isFalse();
        assertThat(store.get().recordDemoMatches).isFalse();
    }

    @Test
    void putSettings_clampsClipPaddingBelowRangeToMin() {
        // A cleared "padding" field arrives as 0; clamp up to the [1,60] floor rather than reject —
        // out-of-range padding only narrows a clip, it never breaks recording.
        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, null, null, null, null, null, null, null, null,
                                null, 0, null));

        assertThat(updated.clipPaddingSeconds()).isEqualTo(1);
        assertThat(store.get().clipPaddingSeconds).isEqualTo(1);
    }

    @Test
    void putSettings_clampsClipPaddingAboveRangeToMax() {
        // Above the [1,60] ceiling clamps down to 60.
        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, null, null, null, null, null, null, null, null,
                                null, 100, null));

        assertThat(updated.clipPaddingSeconds()).isEqualTo(60);
        assertThat(store.get().clipPaddingSeconds).isEqualTo(60);
    }

    @Test
    void putSettings_acceptsDistinctPositiveStorageLocationsAndRoundTrips() {
        // The happy path: distinct, non-nested archive paths with positive caps, plus a positive
        // active cap, are accepted (200) and round-trip through the store.
        String archiveOne = dir("archive-one");
        List<StorageLocation> locs =
                List.of(
                        new StorageLocation("a", archiveOne, 2000),
                        new StorageLocation("b", dir("archive-two"), 4000));

        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, 80, dir("clips"), null, null, null, null, null, null, locs,
                                null, null, null));

        assertThat(updated.retentionCapGb()).isEqualTo(80);
        assertThat(updated.storageLocations()).hasSize(2);
        // Persisted, not just echoed.
        assertThat(store.get().retentionCapGb).isEqualTo(80);
        assertThat(store.get().storageLocations).hasSize(2);
        assertThat(store.get().storageLocations.get(0).path()).isEqualTo(archiveOne);
        assertThat(store.get().storageLocations.get(1).capGb()).isEqualTo(4000);
    }

    // ---- storage path validation (absolute / file / creatable / writable) --

    @Test
    void putSettings_rejectsRelativeVideoDir() {
        // OBS (cwd obs/bin/64bit) and the JVM resolve relative paths against different working
        // directories, so a relative videoDir would record to one folder and play back from another.
        String before = store.get().videoDir;
        assertThatThrownBy(
                        () ->
                                controller.putSettings(
                                        new SettingsPatch(
                                                null, null, null, "relative/clips", null, null, null,
                                                null, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(store.get().videoDir).isEqualTo(before);
    }

    @Test
    void putSettings_rejectsRelativeArchivePath() {
        SettingsPatch patch =
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null,
                        List.of(new StorageLocation("a", "relative/archive", 500)), null, null, null);
        assertThatThrownBy(() -> controller.putSettings(patch))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(store.get().storageLocations).isEmpty();
    }

    @Test
    void putSettings_rejectsVideoDirPointingAtExistingFile() throws Exception {
        // A path that is an existing regular file cannot be a recording folder.
        Path file = tmp.resolve("not-a-folder.mp4");
        java.nio.file.Files.createFile(file);
        assertThatThrownBy(
                        () ->
                                controller.putSettings(
                                        new SettingsPatch(
                                                null, null, null, file.toString(), null, null, null,
                                                null, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        // The file survives the rejected PUT.
        assertThat(java.nio.file.Files.isRegularFile(file)).isTrue();
    }

    @Test
    void putSettings_rejectsArchivePathPointingAtExistingFile() throws Exception {
        Path file = tmp.resolve("not-a-folder.mp4");
        java.nio.file.Files.createFile(file);
        SettingsPatch patch =
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null,
                        List.of(new StorageLocation("a", file.toString(), 500)), null, null, null);
        assertThatThrownBy(() -> controller.putSettings(patch))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(store.get().storageLocations).isEmpty();
    }

    @Test
    void putSettings_rejectsUncreatableVideoDir() throws Exception {
        // A directory nested UNDER an existing regular file can never be created; the PUT must fail
        // at save time (400), not at the first recording.
        Path file = tmp.resolve("blocker.txt");
        java.nio.file.Files.createFile(file);
        assertThatThrownBy(
                        () ->
                                controller.putSettings(
                                        new SettingsPatch(
                                                null, null, null, file.resolve("sub").toString(), null,
                                                null, null, null, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void putSettings_trimsStoragePathWhitespace() {
        // Stray whitespace off a user-typed path is trimmed before persist, so the stored path,
        // OBS's output dir, and the containment guards all agree on one string.
        String clips = dir("clips");
        String archive = dir("archive");
        controller.putSettings(
                new SettingsPatch(
                        null, null, null, "  " + clips + "  ", null, null, null, null, null, null,
                        List.of(new StorageLocation("a", " " + archive + " ", 500)), null, null, null));

        assertThat(store.get().videoDir).isEqualTo(clips);
        assertThat(store.get().storageLocations.get(0).path()).isEqualTo(archive);
    }

    @Test
    void putSettings_createsAMissingVideoDir() {
        // A not-yet-existing (but creatable) folder is accepted and created eagerly, so OBS's first
        // write never races a missing output dir.
        Path clips = tmp.resolve("brand-new-clips");
        assertThat(java.nio.file.Files.exists(clips)).isFalse();

        controller.putSettings(
                new SettingsPatch(
                        null, null, null, clips.toString(), null, null, null, null, null, null, null,
                        null, null, null));

        assertThat(store.get().videoDir).isEqualTo(clips.toString());
        assertThat(java.nio.file.Files.isDirectory(clips)).isTrue();
    }

    // ---- historical archive roots (previousArchiveDirs) --------------------

    @Test
    void putSettings_removingAnArchiveLocation_retainsItAsHistoricalRoot() {
        // VODs the archiver moved onto the drive keep absolute paths under it; removing the location
        // must keep those files streamable + deletable, so the path lands in previousArchiveDirs and
        // StorageRoots still serves it.
        String archive = dir("archive");
        controller.putSettings(
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null,
                        List.of(new StorageLocation("a", archive, 500)), null, null, null));

        controller.putSettings(
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null, List.of(),
                        null, null, null));

        assertThat(store.get().previousArchiveDirs).containsExactly(archive);
        List<String> roots = StorageRoots.of(store.get());
        assertThat(roots).contains(archive);
        assertThat(StorageRoots.isUnder(Path.of(archive, "match-42.mp4"), roots)).isTrue();
    }

    @Test
    void putSettings_editingAnArchivePath_retainsTheOldPath() {
        // Editing a location's path is a remove+add of the same row: the OLD path must be retained,
        // the new one is the active root.
        String oldArchive = dir("archive-old");
        String newArchive = dir("archive-new");
        controller.putSettings(
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null,
                        List.of(new StorageLocation("a", oldArchive, 500)), null, null, null));

        controller.putSettings(
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null,
                        List.of(new StorageLocation("a", newArchive, 500)), null, null, null));

        assertThat(store.get().storageLocations).hasSize(1);
        assertThat(store.get().storageLocations.get(0).path()).isEqualTo(newArchive);
        assertThat(store.get().previousArchiveDirs).containsExactly(oldArchive);
    }

    @Test
    void putSettings_reAddingARetiredArchive_dropsItFromHistorical() {
        // Re-adding the path as an active root drops the historical entry (the active root already
        // grants its access); removing it again re-retains it exactly once — no duplicates.
        String archive = dir("archive");
        SettingsPatch add =
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null,
                        List.of(new StorageLocation("a", archive, 500)), null, null, null);
        SettingsPatch clear =
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null, List.of(),
                        null, null, null);

        controller.putSettings(add);
        controller.putSettings(clear);
        controller.putSettings(add);
        assertThat(store.get().previousArchiveDirs).isEmpty();

        controller.putSettings(clear);
        assertThat(store.get().previousArchiveDirs).containsExactly(archive);
    }

    @Test
    void putSettings_videoDirLandingOnARetiredArchive_dropsItFromHistorical() {
        // A videoDir-only PUT can land the recording dir ON a retained archive dir: it is an active
        // root again, so the historical entry must drop even though this PUT omits storageLocations.
        String archive = dir("archive");
        controller.putSettings(
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null,
                        List.of(new StorageLocation("a", archive, 500)), null, null, null));
        controller.putSettings(
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null, List.of(),
                        null, null, null));
        assertThat(store.get().previousArchiveDirs).containsExactly(archive);

        controller.putSettings(
                new SettingsPatch(
                        null, null, null, archive, null, null, null, null, null, null, null,
                        null, null, null));

        assertThat(store.get().videoDir).isEqualTo(archive);
        assertThat(store.get().previousArchiveDirs).isEmpty();
    }

    @Test
    void putSettings_retiredArchive_dedupesCaseInsensitively() {
        // Removing the same folder twice under different casing retains ONE entry: the canonical
        // (case-folded) form is the dedup key, matching the containment guards.
        String archive = dir("archive");
        SettingsPatch clear =
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null, List.of(),
                        null, null, null);

        controller.putSettings(
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null,
                        List.of(new StorageLocation("a", archive, 500)), null, null, null));
        controller.putSettings(clear);
        controller.putSettings(
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null,
                        List.of(new StorageLocation("a", archive.toUpperCase(java.util.Locale.ROOT), 500)),
                        null, null, null));
        controller.putSettings(clear);

        assertThat(store.get().previousArchiveDirs).hasSize(1);
    }
}
