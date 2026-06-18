package dev.dotarec.bridge;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the local bridge. The renderer is served from a different origin than
 * the core (http://localhost:5173 in dev, file:// when packaged) and calls the
 * REST API on 127.0.0.1:3224, so the browser requires CORS headers to read the
 * responses. The core binds to loopback only, so a permissive policy here is not
 * a network exposure.
 *
 * <p>The GSI connector (:3223) is hit server-side by Dota (no Origin header), so
 * CORS only matters for the browser-facing bridge. The WebSocket handshake has
 * its own allowed-origins in {@link WebSocketConfig}.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
