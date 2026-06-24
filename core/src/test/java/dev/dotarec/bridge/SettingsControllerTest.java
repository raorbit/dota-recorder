package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.bridge.SettingsController.SettingsPatch;
import dev.dotarec.bridge.SettingsController.SettingsView;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import java.nio.file.Path;
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
        controller = new SettingsController(store);
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
                        "resolution", "encoder", "retentionCapGb", "videoDir", "accountId");
    }

    @Test
    void putSettings_roundTripsUserFacingFields() {
        SettingsView updated =
                controller.putSettings(
                        new SettingsPatch("1280x720", "x264", 80, "D:/clips", 96828122L, null));

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
        controller.putSettings(new SettingsPatch("1280x720", null, null, null, null, null));

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
                controller.putSettings(new SettingsPatch(null, null, null, null, null, true));

        assertThat(updated.accountId()).isNull();
        assertThat(store.get().accountId).isNull();
    }

    @Test
    void putSettings_nullAccountIdLeavesItUnchanged() {
        store.update(s -> { s.accountId = 96828122L; return s; });

        // Without the clear flag, a null accountId means "leave unchanged".
        controller.putSettings(new SettingsPatch("1280x720", null, null, null, null, null));

        assertThat(store.get().accountId).isEqualTo(96828122L);
    }
}
