package dev.dotarec.bridge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Confines the Dota GSI connector (127.0.0.1:3223) to the single endpoint it exists for: {@code /gsi}.
 *
 * <p>The GSI connector is a SECOND Tomcat connector on the SAME servlet context as the bridge
 * (3224), so without this every bridge controller -- {@code /settings}, {@code /obs/launch-args},
 * ... -- is also reachable on 3223. {@link BridgeAuthFilter} already requires the token there, but
 * that is authentication, not isolation: when the token is blank (dev / standalone / tests, where
 * auth is off) those endpoints would be wide open on 3223, and the connector's stated purpose -- an
 * isolated surface that can evolve independently of the bridge -- is otherwise untrue.
 *
 * <p>This 404s anything but {@code /gsi} arriving on the GSI port, so 3223 genuinely serves only GSI
 * ingest regardless of whether auth is enabled. It runs ahead of {@link BridgeAuthFilter} (highest
 * precedence) so a stray bridge request on 3223 reads as "not found here" rather than "unauthorized".
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GsiConnectorScopeFilter extends OncePerRequestFilter {

    private final int gsiPort;

    public GsiConnectorScopeFilter(@Value("${app.gsi.port:3223}") int gsiPort) {
        this.gsiPort = gsiPort;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getLocalPort() == gsiPort
                && !BridgeAuthFilter.GSI_PATH.equals(request.getRequestURI())) {
            // Not the GSI endpoint, but it arrived on the GSI connector: this connector serves only
            // /gsi, so the bridge controllers simply do not exist here.
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }
}
