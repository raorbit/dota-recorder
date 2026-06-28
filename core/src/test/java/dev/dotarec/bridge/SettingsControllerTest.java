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
 * and must not appear on the GET/PUT surface; the four user-facing fields are the only writable
 * surface. Wired with a real {@link SettingsStore} against a temp {@link AppPaths} so no Spring
 * context is needed.
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
                        "format");
    }

    @Test
    void putSettings_roundTripsUserFacingFields() {
        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                "1280x720", "x264", 80, "D:/clips", 96828122L, null, null, null,
                                null, null));

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
                                null, null, null, null, null, null, null, 30, "Stream", "mkv"));

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
                                                null)))
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
                                                "garbage", null)))
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
                                                "avi")))
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
                                null, null, null, null, null, null, null, 30, null, null));

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
                        "1280x720", null, null, null, null, null, null, null, null, null));

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
                                null, null, null, null, null, true, null, null, null, null));

        assertThat(updated.accountId()).isNull();
        assertThat(store.get().accountId).isNull();
    }

    @Test
    void putSettings_nullAccountIdLeavesItUnchanged() {
        store.update(s -> { s.accountId = 96828122L; return s; });

        // Without the clear flag, a null accountId means "leave unchanged".
        controller.putSettings(
                new SettingsPatch(
                        "1280x720", null, null, null, null, null, null, null, null, null));

        assertThat(store.get().accountId).isEqualTo(96828122L);
    }

    @Test
    void putSettings_rejectsUnknownAudioSourceKind() {
        SettingsPatch patch =
                new SettingsPatch(
                        null, null, null, null, null, null,
                        List.of(new AudioSource("x", "bogus", "t", "L", 100, false)),
                        null, null, null);
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
                        null, null, null);
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
                                null, null, null, null, null, null, sources, null, null, null));

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
                new SettingsPatch(null, null, null, null, null, null, sources, null, null, null));

        // A later PUT with null audioSources must not touch the stored list.
        controller.putSettings(
                new SettingsPatch(
                        "1280x720", null, null, null, null, null, null, null, null, null));

        assertThat(store.get().audioSources).hasSize(1);
        assertThat(store.get().audioSources.get(0).id()).isEqualTo("id-1");
    }

    @Test
    void putSettings_emptyAudioSources_clearsList() {
        // An explicit empty array clears all sources (distinct from null = unchanged).
        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                null, null, null, null, null, null, List.of(), null, null, null));

        assertThat(updated.audioSources()).isEmpty();
        assertThat(store.get().audioSources).isEmpty();
    }
}
