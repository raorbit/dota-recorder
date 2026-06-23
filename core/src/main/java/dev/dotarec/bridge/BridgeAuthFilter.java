package dev.dotarec.bridge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires a per-launch shared secret on the browser-facing bridge connector (127.0.0.1:3224).
 *
 * <p>The bridge has no other authentication, and binding to loopback does NOT stop a web page open
 * in the user's own browser from issuing cross-origin fetch/WebSocket to 127.0.0.1 -- the request
 * originates from the user's own machine. Electron generates a random token each launch, passes it
 * to the core (env {@code DOTAREC_BRIDGE_TOKEN}) and to the renderer (preload), and the renderer
 * echoes it on every REST call (the {@code X-Dotarec-Token} header) and on the WebSocket handshake
 * (a {@code ?token=} query param, since handshakes can't set headers). A caller without the token
 * gets 401, so a malicious page can neither read the OBS password from {@code /obs/launch-args} nor
 * mutate settings -- and neither can any other local process.
 *
 * <p>Two deliberate exemptions:
 *
 * <ul>
 *   <li>The Dota GSI endpoint ({@code POST /gsi}) is never token-gated -- Dota can't send a header.
 *       The exemption is by REQUEST PATH, not by connector/port: the GSI connector (3223) shares one
 *       servlet context with the bridge connector (3224), so every endpoint (incl. /settings and the
 *       OBS-password-bearing /obs/launch-args) is reachable on BOTH ports. Gating by port would leave
 *       the whole API open token-free on 3223. /gsi stays open on either port -- that endpoint is
 *       inherently unauthenticated (any local process can already post frames to Dota's connector),
 *       so the token protects the data-bearing and state-changing endpoints, not /gsi.</li>
 *   <li>When no token is configured (blank), enforcement is OFF. This keeps the standalone jar,
 *       {@code bootRun}, and tests (which never set the env) working unchanged; Electron always sets
 *       the token, so packaged and dev-with-Electron runs are always protected.</li>
 * </ul>
 *
 * <p>CORS preflight ({@code OPTIONS}) is let through: the browser never attaches the token to a
 * preflight and it carries no readable data, so gating it would only break legitimate writes.
 */
@Component
public class BridgeAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BridgeAuthFilter.class);

    /** REST header the renderer sends the token on. */
    static final String TOKEN_HEADER = "X-Dotarec-Token";

    /** WebSocket-handshake query param the renderer sends the token on (handshakes can't set headers). */
    static final String TOKEN_QUERY_PARAM = "token";

    /** The one endpoint that is never token-gated (Dota can't send a token). Exempt on any port. */
    static final String GSI_PATH = "/gsi";

    private final byte[] expectedToken;
    private final boolean enabled;

    public BridgeAuthFilter(@Value("${app.bridge.token:}") String token) {
        String trimmed = token == null ? "" : token.trim();
        this.enabled = !trimmed.isEmpty();
        this.expectedToken = trimmed.getBytes(StandardCharsets.UTF_8);
        if (!enabled) {
            log.warn(
                    "Bridge auth DISABLED (no DOTAREC_BRIDGE_TOKEN set); the local API is unauthenticated");
        }
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (mustAuthenticate(request) && !tokenMatches(request)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "missing or invalid bridge token");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean mustAuthenticate(HttpServletRequest request) {
        // Off when unconfigured; never gate the GSI endpoint (by path, not port); let preflight through.
        return enabled
                && !GSI_PATH.equals(request.getRequestURI())
                && !HttpMethod.OPTIONS.matches(request.getMethod());
    }

    private boolean tokenMatches(HttpServletRequest request) {
        String provided = request.getHeader(TOKEN_HEADER);
        if (provided == null) {
            // WebSocket handshakes can't set headers; accept the token from the query string instead.
            provided = request.getParameter(TOKEN_QUERY_PARAM);
        }
        if (provided == null) {
            return false;
        }
        // Constant-time compare so a token can't be recovered by response timing (cheap, defensive).
        return MessageDigest.isEqual(expectedToken, provided.getBytes(StandardCharsets.UTF_8));
    }
}
