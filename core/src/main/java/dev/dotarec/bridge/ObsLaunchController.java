package dev.dotarec.bridge;

import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.obs.setup.ObsConfigReadiness;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the OBS launch arguments once the core config bootstrap has completed.
 *
 * <p>Contract: {@code GET /obs/launch-args} returns {@code 409 Conflict} until
 * {@link ObsConfigReadiness} is flipped (the core is still writing OBS config), then {@code 200 OK}
 * with {@code { obsDir, port, password }}. Electron polls this in a retry loop and only spawns
 * {@code obs64.exe} from {@code obsDir} on the configured websocket port/password once it sees 200,
 * so it can never launch OBS against a half-written portable config.
 */
@RestController
public class ObsLaunchController {

    private final ObsConfigReadiness readiness;
    private final SettingsStore settings;
    private final AppPaths paths;

    public ObsLaunchController(
            ObsConfigReadiness readiness, SettingsStore settings, AppPaths paths) {
        this.readiness = readiness;
        this.settings = settings;
        this.paths = paths;
    }

    @GetMapping("/obs/launch-args")
    public ResponseEntity<Map<String, Object>> launchArgs() {
        if (!readiness.isReady()) {
            // Bootstrap not finished; Electron must retry. Empty body is acceptable per the contract.
            return ResponseEntity.status(409).build();
        }
        SettingsStore.Settings s = settings.get();
        if (s.obsPassword == null || s.obsPassword.isBlank()) {
            // Defensive: readiness is only flipped after configure() persists the password, so this
            // should be unreachable. But never hand Electron a blank password (which would launch
            // OBS with auth effectively off); treat as not-ready and let the supervisor retry.
            return ResponseEntity.status(409).build();
        }
        Map<String, Object> body =
                Map.of(
                        "obsDir", paths.obsDir().toAbsolutePath().toString(),
                        "port", s.obsPort,
                        "password", s.obsPassword);
        return ResponseEntity.ok(body);
    }
}
