package dev.dotarec.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Single home for {@code @EnableScheduling}, gated behind {@code app.scheduling.enabled} (default
 * true). Production leaves the property unset, so scheduling is on exactly as before.
 *
 * <p>Why gate it: with scheduling on, the {@code fixedRate}/{@code fixedDelay} jobs that carry no
 * initial delay ({@code ObsConnectionScheduler}, {@code RetentionSweeper}, {@code EnrichmentQueue})
 * fire the instant the scheduler starts at the tail of context refresh — concurrently with the
 * startup {@code ApplicationRunner}s ({@code MigrationRunner}, {@code CrashRecoveryRunner}) on a
 * single-writer SQLite DB. In the full-context {@code @SpringBootTest} that race occasionally
 * surfaced as a {@code SQLiteException} during boot and flaked {@code contextLoads()}. Tests set the
 * property false to suppress the background EXECUTION while still wiring every bean (so a real wiring
 * blocker still fails the smoke test). Disabling this config simply means the {@code @Scheduled}
 * post-processor isn't registered; the scheduler beans themselves are untouched.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
