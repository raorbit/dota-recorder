package dev.dotarec.bridge;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health endpoint the Electron supervisor polls before opening the window.
 *
 * <p>Contract: {@code GET /health} -> 200 with
 * {@code { status, version, dbReady, schemaVersion }}. The supervisor polls this
 * (rather than sleeping a fixed delay) because Spring Boot cold-start time is
 * variable.
 *
 * <p>TODO(plan Step 1/3): source {@code dbReady} and {@code schemaVersion} from
 *   the migration runner once the DB layer lands. For the v0.1 foundation,
 *   {@code schemaVersion} is a constant matching the locked schema and
 *   {@code dbReady} is reported as true (the connection/migration component is
 *   authored by the storage skeleton; wire it in here when available).
 */
@RestController
public class HealthController {

    /**
     * Locked v0.1 schema version. Keep in sync with the migration runner's
     * target {@code PRAGMA user_version}.
     */
    public static final int SCHEMA_VERSION = 1;

    private final String version;

    public HealthController(@Value("${spring.application.name:dota-recorder-core}") String name) {
        // Project version is stamped at build time; fall back to the app name if
        // the implementation version is unavailable (e.g. exploded dev runs).
        String impl = HealthController.class.getPackage().getImplementationVersion();
        this.version = (impl != null) ? impl : "0.1.0";
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "version", version,
                "dbReady", true,
                "schemaVersion", SCHEMA_VERSION);
    }
}
