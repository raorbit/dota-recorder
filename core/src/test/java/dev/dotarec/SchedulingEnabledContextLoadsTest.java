package dev.dotarec;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dotarec.config.SchedulingConfig;
import dev.dotarec.fsm.MatchFsm;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Companion to {@link DotaRecorderApplicationTests} that boots the REAL {@link DotaRecorderApplication}
 * context with scheduling ENABLED (the production default), guarding against boot failures that only
 * surface under {@code @EnableScheduling}.
 *
 * <p>This exists because such a failure already shipped once: {@link MatchFsm} has two constructors
 * (production + a package-private test seam) and, without {@code @Autowired}, Spring resolves it fine
 * with scheduling OFF but fails to instantiate it once the {@code ScheduledAnnotationBeanPostProcessor}
 * participates in bean creation — so the live app wouldn't boot while {@code DotaRecorderApplicationTests}
 * (scheduling disabled) stayed green. This variant flips {@code app.scheduling.enabled=true} so that
 * class of regression fails HERE, in CI.
 *
 * <p>The real {@code @Scheduled} jobs are NOT executed: a no-op {@link TaskScheduler} replaces the
 * scheduler, so {@code @EnableScheduling} still wires its post-processor (the conditions that expose
 * the regression) but no no-initial-delay job fires to race the startup runners on single-writer
 * SQLite — the flake that forced the sibling smoke test to disable scheduling. The config is listed in
 * {@code classes} (not just auto-detected) and the test asserts the scheduler really is the no-op, so
 * the wiring can't silently regress.
 */
@SpringBootTest(
        classes = {
            DotaRecorderApplication.class,
            SchedulingEnabledContextLoadsTest.NoopSchedulerConfig.class,
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
// Close the context after the class so the pooled HikariDataSource releases the temp SQLite file
// before JUnit's static @TempDir cleanup deletes it (an open file blocks deletion on Windows) —
// same rationale as DotaRecorderApplicationTests.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SchedulingEnabledContextLoadsTest {

    @TempDir static Path tmp;

    /** Isolate every on-disk path into the temp dir, and turn scheduling ON (the production default). */
    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> tmp.resolve("data").toString());
        registry.add("app.obs.dir", () -> tmp.resolve("obs").toString());
        registry.add("app.db-path", () -> tmp.resolve("db").resolve("test.sqlite").toString());
        registry.add("app.scheduling.enabled", () -> "true");
    }

    @Autowired ApplicationContext ctx;

    @Test
    void contextLoadsWithSchedulingEnabled() {
        // The boot itself is the assertion: spot-check MatchFsm (the two-constructor bean this variant
        // guards). Confirm scheduling really is enabled, and that our no-op scheduler is the one
        // @EnableScheduling adopted (so a real scheduler can't sneak back in and reintroduce the flake).
        assertThat(ctx.getBean(MatchFsm.class)).isNotNull();
        assertThat(ctx.getBeansOfType(SchedulingConfig.class))
                .as("scheduling must be ENABLED in this variant")
                .isNotEmpty();
        assertThat(ctx.getBean(TaskScheduler.class))
                .as("the no-op scheduler must be in use so @Scheduled jobs never run during the test")
                .isInstanceOf(NoopTaskScheduler.class);
    }

    /**
     * Supplies the no-op {@link TaskScheduler} that {@code @EnableScheduling} adopts (it prefers a
     * user-provided bean). Registers every task, runs none. {@code @TestConfiguration} keeps it out of
     * the component scan; it is applied here via the {@code classes} list above.
     */
    @TestConfiguration
    static class NoopSchedulerConfig {
        @Bean
        TaskScheduler taskScheduler() {
            return new NoopTaskScheduler();
        }
    }

    /** A {@link TaskScheduler} that accepts every task and executes none. */
    private static final class NoopTaskScheduler implements TaskScheduler {
        private static final ScheduledFuture<Object> DONE = new NoopScheduledFuture();

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return DONE;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return DONE;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return DONE;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return DONE;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return DONE;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return DONE;
        }
    }

    /** An already-completed, do-nothing future returned by {@link NoopTaskScheduler}. */
    private static final class NoopScheduledFuture implements ScheduledFuture<Object> {
        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed o) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
