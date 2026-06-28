package dev.dotarec.clip;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Defines the bounded pool the {@code @Async("clipExecutor")} clip generator runs on.
 *
 * <p>{@code @EnableAsync} is already present on {@link dev.dotarec.bridge.WebSocketConfig}; this class
 * only contributes the {@code clipExecutor} bean. Modeled on that config's {@code enrichExecutor}.
 *
 * <p>The pool is deliberately small (core=1, max=2): an ffmpeg cut is CPU/IO heavy and there is little
 * benefit to running many concurrently on a single-user desktop. A 50-deep queue absorbs a burst of
 * auto-clips minted at match finalize (e.g. several rampages). On the (implausible) event the queue
 * fills, the dispatch is <em>rejected</em> rather than run inline: auto-clips are dispatched from the
 * synchronized {@code MatchFsm.finalizeRecording} on the GSI thread, so a {@code CallerRunsPolicy}
 * could otherwise run a full cut while holding the FSM monitor and stall recording. A rejected clip
 * is never lost — its row stays {@code pending} and {@link ClipQueue} re-dispatches it within ~60s.
 */
@Configuration
public class ClipAsyncConfig {

    @Bean("clipExecutor")
    public TaskExecutor clipExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("clip-");
        // Abort (not CallerRuns) on saturation: the caller may be the synchronized FSM finalize on the
        // GSI thread, which must never block on a cut. The rejected row stays pending; ClipQueue retries.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
