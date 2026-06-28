package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        controller = new SettingsController(store, obsController);
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
                        "audioSources");
    }

    @Test
    void putSettings_roundTripsUserFacingFields() {
        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(
                                "1280x720", "x264", 80, "D:/clips", 96828122L, null, null));

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
    void putSettings_preservesAppManagedObsPassword() {
        // Seed an app-generated, non-default password through the store.
        store.update(
                s -> {
                    s.obsPassword = "abc1234567890def";
                    s.obsPort = 4466;
                    return s;
                });

        // PUT an unrelated, user-facing field only.
        controller.putSettings(new SettingsPatch("1280x720", null, null, null, null, null, null));

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
                controller.putSettings(new SettingsPatch(null, null, null, null, null, true, null));

        assertThat(updated.accountId()).isNull();
        assertThat(store.get().accountId).isNull();
    }

    @Test
    void putSettings_nullAccountIdLeavesItUnchanged() {
        store.update(s -> { s.accountId = 96828122L; return s; });

        // Without the clear flag, a null accountId means "leave unchanged".
        controller.putSettings(new SettingsPatch("1280x720", null, null, null, null, null, null));

        assertThat(store.get().accountId).isEqualTo(96828122L);
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
                        new SettingsPatch(null, null, null, null, null, null, sources));

        assertThat(updated.audioSources()).hasSize(2);
        assertThat(store.get().audioSources).hasSize(2);
        assertThat(store.get().audioSources.get(1).target()).isEqualTo("::Discord.exe");
        assertThat(store.get().audioSources.get(1).muted()).isTrue();
    }

    @Test
    void putSettings_nullAudioSources_leavesListUnchanged() {
        List<AudioSource> sources =
                List.of(new AudioSource("id-1", "input", "mic", "Mic", 50, false));
        controller.putSettings(new SettingsPatch(null, null, null, null, null, null, sources));

        // A later PUT with null audioSources must not touch the stored list.
        controller.putSettings(new SettingsPatch("1280x720", null, null, null, null, null, null));

        assertThat(store.get().audioSources).hasSize(1);
        assertThat(store.get().audioSources.get(0).id()).isEqualTo("id-1");
    }

    @Test
    void putSettings_emptyAudioSources_clearsList() {
        // An explicit empty array clears all sources (distinct from null = unchanged).
        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch(null, null, null, null, null, null, List.of()));

        assertThat(updated.audioSources()).isEmpty();
        assertThat(store.get().audioSources).isEmpty();
    }
}
