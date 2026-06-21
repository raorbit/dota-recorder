package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.obs.setup.ObsConfigReadiness;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@link ObsLaunchController} -- the deterministic 409->200 readiness gate and the
 * launch-args JSON shape, the two contract details Electron's poll loop depends on. Wired with real
 * collaborators against a temp {@link AppPaths}, so no Spring context or live OBS is needed.
 */
class ObsLaunchControllerTest {

    private ObsConfigReadiness readiness;
    private SettingsStore settings;
    private AppPaths paths;
    private ObsLaunchController controller;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        // Point both the data root and the OBS dir at the temp dir so AppPaths creates nothing under
        // the real %APPDATA%/%LOCALAPPDATA% during the test.
        paths =
                new AppPaths(
                        tmp.resolve("data").toString(), tmp.resolve("obs").toString());
        settings = new SettingsStore(paths);
        readiness = new ObsConfigReadiness();
        controller = new ObsLaunchController(readiness, settings, paths);
    }

    @Test
    void notReady_returns409_withNoBody() {
        ResponseEntity<Map<String, Object>> resp = controller.launchArgs();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).isNull();
    }

    @Test
    void afterMarkReady_returns200_withLaunchArgsJsonShape() {
        settings.update(
                s -> {
                    s.obsPort = 4466;
                    s.obsPassword = "abc1234567890def";
                    return s;
                });
        readiness.markReady();

        ResponseEntity<Map<String, Object>> resp = controller.launchArgs();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.keySet()).containsExactlyInAnyOrder("obsDir", "port", "password");
        assertThat(body.get("obsDir"))
                .isEqualTo(paths.obsDir().toAbsolutePath().toString());
        assertThat(body.get("port")).isEqualTo(4466);
        assertThat(body.get("password")).isEqualTo("abc1234567890def");
    }

    @Test
    void readiness_isOneWayLatch_falseUntilMarked() {
        // The 409->200 transition only ever goes one way within a boot.
        assertThat(readiness.isReady()).isFalse();
        assertThat(controller.launchArgs().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Credentials are persisted by configure() before readiness flips; mirror that so the
        // controller's blank-password guard doesn't (correctly) keep the endpoint at 409.
        settings.update(
                s -> {
                    s.obsPassword = "abc1234567890def";
                    return s;
                });
        readiness.markReady();

        assertThat(readiness.isReady()).isTrue();
        assertThat(controller.launchArgs().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ready_butBlankPassword_returns409() {
        // Defensive guard: even if readiness is somehow marked before credentials are persisted,
        // the endpoint must never hand Electron a blank password (auth effectively off).
        readiness.markReady();
        settings.update(
                s -> {
                    s.obsPassword = "";
                    return s;
                });

        assertThat(controller.launchArgs().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
