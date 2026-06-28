package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.dotarec.data.MigrationRunner;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Locks the {@code /health} contract the Electron supervisor polls and the renderer's {@code Health}
 * type declares: the JSON keys, and that {@code dbReady}/{@code schemaVersion} mirror the
 * {@link MigrationRunner} rather than the old hardcoded values.
 */
class HealthControllerTest {

    @Test
    void health_reportsMigrationReadinessAndSchemaVersion() {
        MigrationRunner migrations = mock(MigrationRunner.class);
        when(migrations.isReady()).thenReturn(true);
        when(migrations.currentSchemaVersion()).thenReturn(3);

        Map<String, Object> body = new HealthController(migrations).health();

        assertThat(body.get("status")).isEqualTo("ok");
        assertThat(body.get("dbReady")).isEqualTo(true);
        assertThat(body.get("schemaVersion")).isEqualTo(3);
        assertThat(body).containsKey("version");
    }

    @Test
    void health_reportsNotReadyWhileMigrationsAreStillRunning() {
        // The web server accepts requests before the @Order(0) migration runner finishes, so dbReady
        // must be able to report false instead of the old hardcoded true.
        MigrationRunner migrations = mock(MigrationRunner.class);
        when(migrations.isReady()).thenReturn(false);
        when(migrations.currentSchemaVersion()).thenReturn(1);

        Map<String, Object> body = new HealthController(migrations).health();

        assertThat(body.get("status")).isEqualTo("ok");
        assertThat(body.get("dbReady")).isEqualTo(false);
        assertThat(body.get("schemaVersion")).isEqualTo(1);
    }
}
