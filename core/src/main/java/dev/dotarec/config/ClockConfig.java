package dev.dotarec.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the single {@link TimeSource} bean the recording path injects. One bean (not two bare
 * {@link java.util.function.LongSupplier}s, which would collide by type) so {@link
 * dev.dotarec.fsm.MatchFsm} can take a single constructor with no {@code @Autowired}-selected test
 * seam. Always registered (unlike {@link SchedulingConfig}, which is conditional), since the clock is
 * needed whether or not scheduling runs.
 */
@Configuration
public class ClockConfig {

    /** Production clocks: monotonic {@code System::nanoTime} + wall {@code System::currentTimeMillis}. */
    @Bean
    public TimeSource timeSource() {
        return TimeSource.system();
    }
}
