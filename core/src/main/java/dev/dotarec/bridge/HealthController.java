package dev.dotarec.bridge;

import dev.dotarec.data.MigrationRunner;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health endpoint the Electron supervisor polls before opening the window.
 *
 * <p>Contract: {@code GET /health} -> 200 with {@code { status, version, dbReady, schemaVersion }}.
 * The supervisor polls this (rather than sleeping a fixed delay) because Spring Boot cold-start time
 * is variable.
 *
 * <p>{@code dbReady} and {@code schemaVersion} are sourced from the {@link MigrationRunner}, so they
 * report the REAL migration state: {@code dbReady} is false until startup migrations finish (the
 * embedded web server accepts requests before the {@code @Order(0)} migration runner completes), and
 * {@code schemaVersion} tracks the latest applied {@code PRAGMA user_version} rather than a constant
 * that silently drifts from the migrator's target.
 */
@RestController
public class HealthController {

    private final String version;
    private final MigrationRunner migrations;

    public HealthController(MigrationRunner migrations) {
        this.migrations = migrations;
        // Project version is stamped at build time; fall back to a literal if the implementation
        // version is unavailable (e.g. exploded dev runs).
        String impl = HealthController.class.getPackage().getImplementationVersion();
        this.version = (impl != null) ? impl : "0.1.1";
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "version", version,
                "dbReady", migrations.isReady(),
                "schemaVersion", migrations.currentSchemaVersion());
    }
}
