package dev.dotarec;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dotarec.bridge.MatchController;
import dev.dotarec.config.SchedulingConfig;
import dev.dotarec.data.MigrationRunner;
import dev.dotarec.obs.ObsController;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the REAL {@link DotaRecorderApplication} context end-to-end, fully isolated to a temp dir so
 * it never touches the user's real DB / settings / OBS install and binds no real port. This is the
 * test the old {@code @SpringBootTest} (a stand-in {@code TestApp}) never was: it wires every
 * production bean -- the FSM, tagger, OBS client, schedulers and ApplicationRunners -- so a latent
 * boot blocker (a {@code @Lazy}-needing bean cycle, an ambiguous unautowired constructor, a missing
 * bean) fails HERE, in CI, rather than only when first run live against a real OBS.
 *
 * <p>Offline-safe by construction: a down OBS never throws ({@code ObsController.ensureConnected}
 * returns false, {@code ObsConfigBootstrap}/{@code ObsConnectionScheduler} catch their own
 * exceptions), and the crash-recovery / migration runners operate on the temp DB. {@code
 * webEnvironment=NONE} keeps both connectors (3223/3224) from binding so the test can't clash with a
 * running instance or trip the firewall.
 */
@SpringBootTest(
        classes = DotaRecorderApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DotaRecorderApplicationTests {

    @TempDir static Path tmp;

    /**
     * Redirect every on-disk path into the temp dir BEFORE the context refreshes. JUnit resolves a
     * static {@code @TempDir} before the dynamic source runs, and {@code @DynamicPropertySource} is
     * applied before context start, so {@code AppPaths} / {@code DataSourceConfig} read these values.
     *
     * <p>{@code app.db-path} is REQUIRED and independent of {@code app.data-dir}: {@code
     * DataSourceConfig} reads {@code app.db-path} (defaulting to the real {@code %APPDATA%} DB when
     * unset), so without this line the test would migrate/write the user's real SQLite file.
     */
    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> tmp.resolve("data").toString());
        registry.add("app.obs.dir", () -> tmp.resolve("obs").toString());
        registry.add("app.db-path", () -> tmp.resolve("db").resolve("test.sqlite").toString());
        // Wire every bean but DON'T let the background jobs run: the no-initial-delay schedulers
        // (OBS reconnect, retention sweep, enrichment) otherwise fire at the tail of context refresh
        // and race the startup runners (migration, crash recovery) on single-writer SQLite, which
        // intermittently threw a SQLiteException during boot and flaked this test.
        registry.add("app.scheduling.enabled", () -> "false");
    }

    @Autowired ApplicationContext ctx;

    @Test
    void contextLoads() {
        // The boot itself is the assertion; spot-check a few key beans so a wiring failure is clearer.
        assertThat(ctx.getBean(MatchController.class)).isNotNull();
        assertThat(ctx.getBean(ObsController.class)).isNotNull();
        assertThat(ctx.getBean(MigrationRunner.class)).isNotNull();
        // Scheduling must be OFF here (app.scheduling.enabled=false), so the background jobs can't
        // race the boot. Guards against the property/config being dropped and the flake returning.
        assertThat(ctx.getBeansOfType(SchedulingConfig.class))
                .as("scheduling must be disabled in the smoke test to avoid the boot DB race")
                .isEmpty();
    }
}
