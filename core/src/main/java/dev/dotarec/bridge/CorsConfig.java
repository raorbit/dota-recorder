package dev.dotarec.bridge;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the local bridge. The renderer is served from a different origin than
 * the core (http://localhost:5173 in dev, file:// when packaged) and calls the
 * REST API on 127.0.0.1:3224, so the browser requires CORS headers to read the
 * responses.
 *
 * <p>Origins are restricted to exactly the renderer's: the dev Vite server and the
 * packaged file:// page (whose Origin is {@code file://} or the literal {@code null}).
 * Defense-in-depth — the per-launch bridge token is the real gate — but with {@code *}
 * a malicious page the user visits could read responses if the tokenless dev/standalone
 * mode were running. Scoping the origins closes that and guards future changes (e.g. if
 * credentialed CORS were ever added).
 *
 * <p>The GSI connector (:3223) is hit server-side by Dota (no Origin header), so
 * CORS only matters for the browser-facing bridge. The WebSocket handshake has
 * its own allowed-origins in {@link WebSocketConfig}.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Renderer origins shared with WebSocketConfig via BridgeOrigins (one list, no drift).
        registry.addMapping("/**")
                .allowedOriginPatterns(BridgeOrigins.patterns())
                .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
