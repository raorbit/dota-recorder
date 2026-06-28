package dev.dotarec.bridge;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the live-status WebSocket the Electron UI subscribes to.
 *
 * <p>Plan (Renderer UI / State Management): the UI "subscribes to live status + 'new match
 * recorded' events over WebSocket". The handler is exposed at {@code /ws} on the bridge
 * connector (127.0.0.1:3224).
 *
 * <p>Also enables {@code @Async} (Enricher) here and defines the bounded {@code enrichExecutor} the
 * enricher runs on. {@code @Scheduled} is enabled separately by {@link dev.dotarec.config.SchedulingConfig}
 * (gated behind {@code app.scheduling.enabled} so tests can disable the background jobs).
 */
@Configuration
@EnableWebSocket
@EnableAsync
public class WebSocketConfig implements WebSocketConfigurer {

    private final StatusWebSocket statusWebSocket;

    public WebSocketConfig(StatusWebSocket statusWebSocket) {
        this.statusWebSocket = statusWebSocket;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(statusWebSocket, "/ws").setAllowedOrigins("*");
    }

    /**
     * Bounded pool the {@code @Async("enrichExecutor")} {@link dev.dotarec.enrich.Enricher} runs on.
     * Small (core=max=2) to respect OpenDota's rate limits (~60 req/min anon); a 100-deep queue
     * absorbs a poll burst, and {@code CallerRunsPolicy} throttles the scheduler thread rather than
     * dropping work if the queue ever fills.
     */
    @Bean("enrichExecutor")
    public TaskExecutor enrichExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("enrich-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
