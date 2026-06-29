package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

/**
 * Locks in the renderer origin allow-list shared by {@link CorsConfig} and {@link WebSocketConfig}.
 *
 * <p>Both Spring CORS and the WebSocket handshake evaluate {@code allowedOriginPatterns} through the
 * same {@link CorsConfiguration#checkOrigin} matcher, so exercising that matcher here faithfully
 * reproduces what the live bridge does for both REST and {@code /ws}.
 *
 * <p>The headline guard is {@code "file://"} (bare, host-less): a packaged Electron page sends that
 * exact Origin on its WebSocket handshake, the wildcard {@code "file://*"} does NOT match it, and a
 * regression there silently wedges the packaged UI on "connecting…" while REST keeps working.
 */
class BridgeOriginsTest {

    private static CorsConfiguration config() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(BridgeOrigins.RENDERER_PATTERNS);
        return cfg;
    }

    @Test
    void allowsThePackagedWebSocketBareFileOrigin() {
        // The exact Origin a file:// page sends on a WebSocket handshake (Chromium, host-less).
        assertThat(config().checkOrigin("file://")).isEqualTo("file://");
    }

    @Test
    void allowsTheOtherRendererOrigins() {
        CorsConfiguration cfg = config();
        // fetch() from a file:// page sends the opaque origin.
        assertThat(cfg.checkOrigin("null")).isEqualTo("null");
        // A pathful file origin (covered by the file://* wildcard), for robustness across Chromium builds.
        assertThat(cfg.checkOrigin("file:///C:/Users/x/index.html")).isNotNull();
        // Dev Vite server (both host spellings).
        assertThat(cfg.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(cfg.checkOrigin("http://127.0.0.1:5173")).isEqualTo("http://127.0.0.1:5173");
    }

    @Test
    void rejectsUnknownOrigins() {
        CorsConfiguration cfg = config();
        // A page the user merely visits must not be able to talk to the bridge.
        assertThat(cfg.checkOrigin("https://evil.example")).isNull();
        // A different localhost port (e.g. some other local dev server) is not the renderer.
        assertThat(cfg.checkOrigin("http://localhost:3000")).isNull();
    }
}
