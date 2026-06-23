package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link BridgeAuthFilter}: every bridge endpoint requires the per-launch token, the
 * GSI endpoint and CORS preflight are exempt, the WebSocket handshake authenticates via query param,
 * and a blank token disables enforcement (dev / standalone / tests). The exemption is by PATH, not
 * connector port -- both Tomcat connectors share one servlet context, so a port-based exemption
 * would leave the whole API open token-free on the GSI port.
 */
class BridgeAuthFilterTest {

    private static final int BRIDGE_PORT = 3224;
    private static final int GSI_PORT = 3223;
    private static final String TOKEN = "s3cr3t-token";

    private static MockHttpServletResponse run(BridgeAuthFilter filter, MockHttpServletRequest req)
            throws ServletException, IOException {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        return resp;
    }

    private static MockHttpServletRequest req(String method, String uri, int localPort) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setLocalPort(localPort);
        return r;
    }

    @Test
    void rejectsBridgeRequestWithoutToken() throws Exception {
        MockHttpServletResponse resp =
                run(new BridgeAuthFilter(TOKEN), req("GET", "/settings", BRIDGE_PORT));
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsBridgeRequestWithWrongToken() throws Exception {
        MockHttpServletRequest r = req("GET", "/obs/launch-args", BRIDGE_PORT);
        r.addHeader(BridgeAuthFilter.TOKEN_HEADER, "not-the-token");
        assertThat(run(new BridgeAuthFilter(TOKEN), r).getStatus()).isEqualTo(401);
    }

    @Test
    void allowsBridgeRequestWithCorrectHeaderToken() throws Exception {
        MockHttpServletRequest r = req("GET", "/obs/launch-args", BRIDGE_PORT);
        r.addHeader(BridgeAuthFilter.TOKEN_HEADER, TOKEN);
        assertThat(run(new BridgeAuthFilter(TOKEN), r).getStatus()).isEqualTo(200);
    }

    @Test
    void allowsWebSocketHandshakeWithQueryParamToken() throws Exception {
        // Browser WebSocket handshakes can't set headers, so the token rides in the query string.
        MockHttpServletRequest r = req("GET", "/ws", BRIDGE_PORT);
        r.addParameter(BridgeAuthFilter.TOKEN_QUERY_PARAM, TOKEN);
        assertThat(run(new BridgeAuthFilter(TOKEN), r).getStatus()).isEqualTo(200);
    }

    @Test
    void neverGatesTheGsiEndpoint() throws Exception {
        // Dota posts to /gsi and can't send a token; exempt by path, on either connector.
        assertThat(run(new BridgeAuthFilter(TOKEN), req("POST", "/gsi", GSI_PORT)).getStatus())
                .isEqualTo(200);
        assertThat(run(new BridgeAuthFilter(TOKEN), req("POST", "/gsi", BRIDGE_PORT)).getStatus())
                .isEqualTo(200);
    }

    @Test
    void gatesBridgeEndpointsOnTheGsiPortToo() throws Exception {
        // Regression: both connectors share one servlet context, so /obs/launch-args is reachable on
        // the GSI port (3223). A port-based exemption would leak the OBS password there token-free.
        assertThat(run(new BridgeAuthFilter(TOKEN), req("GET", "/obs/launch-args", GSI_PORT)).getStatus())
                .isEqualTo(401);
    }

    @Test
    void letsCorsPreflightThrough() throws Exception {
        assertThat(run(new BridgeAuthFilter(TOKEN), req("OPTIONS", "/settings", BRIDGE_PORT)).getStatus())
                .isEqualTo(200);
    }

    @Test
    void disabledWhenNoTokenConfigured() throws Exception {
        // Blank token (dev / standalone jar / tests): enforcement is off, even with no token sent.
        assertThat(run(new BridgeAuthFilter(""), req("GET", "/settings", BRIDGE_PORT)).getStatus())
                .isEqualTo(200);
    }
}
