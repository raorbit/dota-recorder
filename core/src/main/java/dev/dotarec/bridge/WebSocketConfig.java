package dev.dotarec.bridge;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
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
 * <p>Also enables {@code @Async} (Enricher) and {@code @Scheduled} (RetentionSweeper) here so the
 * main application class need not carry those annotations.
 */
@Configuration
@EnableWebSocket
@EnableAsync
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {

    private final StatusWebSocket statusWebSocket;

    public WebSocketConfig(StatusWebSocket statusWebSocket) {
        this.statusWebSocket = statusWebSocket;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(statusWebSocket, "/ws").setAllowedOrigins("*");
    }
}
