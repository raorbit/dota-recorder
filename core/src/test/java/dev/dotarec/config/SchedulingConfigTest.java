package dev.dotarec.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Pins the {@code app.scheduling.enabled} gate on {@link SchedulingConfig}: ON by default (so
 * production, which never sets the property, keeps its background jobs) and OFF only when explicitly
 * disabled (how tests dodge the boot-time scheduler/SQLite race). Uses an {@link ApplicationContextRunner}
 * so it exercises the conditional directly — no full app boot, no DB.
 */
class SchedulingConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(SchedulingConfig.class);

    @Test
    void schedulingEnabledByDefaultWhenPropertyAbsent() {
        // Production never sets the property -> matchIfMissing keeps scheduling ON.
        runner.run(ctx -> assertThat(ctx).hasSingleBean(SchedulingConfig.class));
    }

    @Test
    void schedulingEnabledWhenPropertyTrue() {
        runner.withPropertyValues("app.scheduling.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(SchedulingConfig.class));
    }

    @Test
    void schedulingDisabledWhenPropertyFalse() {
        runner.withPropertyValues("app.scheduling.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SchedulingConfig.class));
    }
}
