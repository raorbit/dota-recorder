package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private SettingsStore store;
    private SettingsController controller;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        AppPaths paths =
                new AppPaths(tmp.resolve("data").toString(), tmp.resolve("obs").toString());
        store = new SettingsStore(paths);
        // OBS not connected in unit tests: reconcileAudioOnDemand is a no-op, so the PUT never 500s.
        ObsController obsController = mock(ObsController.class);
        when(obsController.ensureConnected()).thenReturn(false);
        // applyProfile() is a best-effort profile re-write after a PUT; mock it so the test never
        // touches the OBS dir on disk and the swallowed try/catch is exercised cleanly.
        ObsConfigWriter obsConfigWriter = mock(ObsConfigWriter.class);
        controller = new SettingsController(store, obsController, obsConfigWriter);
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
                        "clipPaddingSeconds");
    }

    @Test
    void putSettings_roundTripsUserFacingFields() {
        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                "1280x720", "x264", 80, "D:/clips", 96828122L, null, null, null,
                                null, null, null, null, null));

        assertThat(updated.resolution()).isEqualTo("1280x720");
        assertThat(updated.encoder()).isEqualTo("x264");
        assertThat(updated.retentionCapGb()).isEqualTo(80);
        assertThat(updated.videoDir()).isEqualTo("D:/clips");
        assertThat(updated.accountId()).isEqualTo(96828122L);

        // Persisted to the store, not just echoed.
        assertThat(store.get().resolution).isEqualTo("1280x720");
        assertThat(store.get().encoder).isEqualTo("x264");
        assertThat(store.get().retentionCapGb).isEqualTo(80);
        assertThat(store.get().videoDir).isEqualTo("D:/clips");
        assertThat(store.get().accountId).isEqualTo(96828122L);
    }

    @Test
    void putSettings_roundTripsVideoControls() {
        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, null, null, null, null, 30, "Stream", "mkv", null,
                                null, null));

        assertThat(updated.fps()).isEqualTo(30);
        assertThat(updated.quality()).isEqualTo("Stream");
        assertThat(updated.format()).isEqualTo("mkv");
        assertThat(store.get().fps).isEqualTo(30);
        assertThat(store.get().quality).isEqualTo("Stream");
        assertThat(store.get().format).isEqualTo("mkv");
    }

    @Test
    void putSettings_rejectsInvalidFps() {
        // A garbage fps would write a broken OBS [Video] FPSCommon -> abort every match. Reject it.
        assertThatThrownBy(
                        () ->
                                controller.putSettings(
                                        new SettingsPatch(
                                                null, null, null, null, null, null, null, 144, null,
                                                null, null, null, null)))
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
                                                "garbage", null, null, null, null)))
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
                                                "avi", null, null, null)))
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
                                null, null));

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
                        null, null));

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
                                null, null));

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
                                                null, null, null, null, null, null)))
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
                                                null, null, null, null)))
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
                        null, null));

        assertThat(store.get().accountId).isEqualTo(96828122L);
    }

    @Test
    void putSettings_rejectsUnknownAudioSourceKind() {
        SettingsPatch patch =
                new SettingsPatch(
                        null, null, null, null, null, null,
                        List.of(new AudioSource("x", "bogus", "t", "L", 100, false)),
                        null, null, null, null, null, null);
        assertThatThrownBy(() -> controller.putSettings(patch))
                .isInstanceOf(ResponseStatusException.class);
        // Rejected before persist: still the seeded Dota default, list not replaced.
        assertThat(store.get().audioSources).hasSize(1);
    }

    @Test
    void putSettings_rejectsOutOfRangeAudioVolume() {
        SettingsPatch patch =
                new SettingsPatch(
                        null, null, null, null, null, null,
                        List.of(new AudioSource("x", "output", "default", "L", 150, false)),
                        null, null, null, null, null, null);
        assertThatThrownBy(() -> controller.putSettings(patch))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(store.get().audioSources).hasSize(1);
    }

    @Test
    void getSettings_audioSourcesAlwaysNonEmptyOnFreshInstall() {
        // The fresh-install seed: exactly one Dota application-capture source so a fresh install
        // records the game's audio out of the box.
        SettingsView view = controller.getSettings();
        assertThat(view.audioSources()).hasSize(1);
        AudioSource seed = view.audioSources().get(0);
        assertThat(seed.kind()).isEqualTo("application");
        assertThat(seed.target()).isEqualTo("::dota2.exe");
        assertThat(seed.volume()).isEqualTo(100);
        assertThat(seed.muted()).isFalse();
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
                                null, null));

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
                        null));

        // A later PUT with null audioSources must not touch the stored list.
        controller.putSettings(
                new SettingsPatch(
                        "1280x720", null, null, null, null, null, null, null, null, null, null,
                        null, null));

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
                                null, null));

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
        List<StorageLocation> locs =
                List.of(
                        new StorageLocation("a", "E:/dota-archive", 2000),
                        new StorageLocation("b", "F:/dota-archive", 4000));

        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, null, null, null, null, null, null, null, locs,
                                null, null));

        assertThat(updated.storageLocations()).hasSize(2);
        assertThat(store.get().storageLocations).hasSize(2);
        assertThat(store.get().storageLocations.get(1).path()).isEqualTo("F:/dota-archive");
        assertThat(store.get().storageLocations.get(1).capGb()).isEqualTo(4000);
    }

    @Test
    void putSettings_nullStorageLocations_leavesListUnchanged() {
        controller.putSettings(
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null,
                        List.of(new StorageLocation("a", "E:/archive", 500)), null, null));

        // A later PUT with null storageLocations must not touch the stored list.
        controller.putSettings(
                new SettingsPatch(
                        "1280x720", null, null, null, null, null, null, null, null, null, null,
                        null, null));

        assertThat(store.get().storageLocations).hasSize(1);
        assertThat(store.get().storageLocations.get(0).path()).isEqualTo("E:/archive");
    }

    @Test
    void putSettings_emptyStorageLocations_clearsList() {
        controller.putSettings(
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null,
                        List.of(new StorageLocation("a", "E:/archive", 500)), null, null));

        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, null, null, null, null, null, null, null, List.of(),
                                null, null));

        assertThat(updated.storageLocations()).isEmpty();
        assertThat(store.get().storageLocations).isEmpty();
    }

    @Test
    void putSettings_rejectsBlankStorageLocationPath() {
        SettingsPatch patch =
                new SettingsPatch(
                        null, null, null, null, null, null, null, null, null, null,
                        List.of(new StorageLocation("a", "  ", 500)), null, null);
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
                        List.of(new StorageLocation("a", "E:/archive", 0)), null, null);
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
                        null, null);
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
                        List.of(new StorageLocation("a", "D:/clips", 500)), null, null);
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
                        List.of(new StorageLocation("a", "D:/rec/archive", 500)), null, null);
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
                        null, null);
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
                        List.of(new StorageLocation("a", ".", 500)), null, null);
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
                                                null, null, null, null)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        // The store keeps its default 50 GiB cap, not a partially-applied 0.
        assertThat(store.get().retentionCapGb).isEqualTo(50);
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
                                                null, null, null, null)))
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
                                true, 12));

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
                                false, 45));

        assertThat(off.autoClipOnRampage()).isFalse();
        assertThat(off.clipPaddingSeconds()).isEqualTo(45);
        assertThat(store.get().autoClipOnRampage).isFalse();
        assertThat(store.get().clipPaddingSeconds).isEqualTo(45);
    }

    @Test
    void putSettings_clampsClipPaddingBelowRangeToMin() {
        // A cleared "padding" field arrives as 0; clamp up to the [1,60] floor rather than reject —
        // out-of-range padding only narrows a clip, it never breaks recording.
        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, null, null, null, null, null, null, null, null,
                                null, 0));

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
                                null, 100));

        assertThat(updated.clipPaddingSeconds()).isEqualTo(60);
        assertThat(store.get().clipPaddingSeconds).isEqualTo(60);
    }

    @Test
    void putSettings_acceptsDistinctPositiveStorageLocationsAndRoundTrips() {
        // The happy path: distinct, non-nested archive paths with positive caps, plus a positive
        // active cap, are accepted (200) and round-trip through the store.
        List<StorageLocation> locs =
                List.of(
                        new StorageLocation("a", "E:/archive-one", 2000),
                        new StorageLocation("b", "F:/archive-two", 4000));

        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, 80, "D:/clips", null, null, null, null, null, null, locs,
                                null, null));

        assertThat(updated.retentionCapGb()).isEqualTo(80);
        assertThat(updated.storageLocations()).hasSize(2);
        // Persisted, not just echoed.
        assertThat(store.get().retentionCapGb).isEqualTo(80);
        assertThat(store.get().storageLocations).hasSize(2);
        assertThat(store.get().storageLocations.get(0).path()).isEqualTo("E:/archive-one");
        assertThat(store.get().storageLocations.get(1).capGb()).isEqualTo(4000);
    }
}
