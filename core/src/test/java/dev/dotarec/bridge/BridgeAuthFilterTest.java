package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link BridgeAuthFilter}: the bridge connector requires the per-launch token, the
 * GSI connector and CORS preflight are exempt, the WebSocket handshake authenticates via query
 * param, and a blank token disables enforcement entirely (dev / standalone / tests).
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

    private static MockHttpServletRequest onBridge(String method, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setLocalPort(BRIDGE_PORT);
        return req;
    }

    @Test
    void rejectsBridgeRequestWithoutToken() throws Exception {
        MockHttpServletResponse resp = run(new BridgeAuthFilter(TOKEN, BRIDGE_PORT), onBridge("GET", "/settings"));
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsBridgeRequestWithWrongToken() throws Exception {
        MockHttpServletRequest req = onBridge("GET", "/obs/launch-args");
        req.addHeader(BridgeAuthFilter.TOKEN_HEADER, "not-the-token");
        assertThat(run(new BridgeAuthFilter(TOKEN, BRIDGE_PORT), req).getStatus()).isEqualTo(401);
    }

    @Test
    void allowsBridgeRequestWithCorrectHeaderToken() throws Exception {
        MockHttpServletRequest req = onBridge("GET", "/obs/launch-args");
        req.addHeader(BridgeAuthFilter.TOKEN_HEADER, TOKEN);
        assertThat(run(new BridgeAuthFilter(TOKEN, BRIDGE_PORT), req).getStatus()).isEqualTo(200);
    }

    @Test
    void allowsWebSocketHandshakeWithQueryParamToken() throws Exception {
        // Browser WebSocket handshakes can't set headers, so the token rides in the query string.
        MockHttpServletRequest req = onBridge("GET", "/ws");
        req.addParameter(BridgeAuthFilter.TOKEN_QUERY_PARAM, TOKEN);
        assertThat(run(new BridgeAuthFilter(TOKEN, BRIDGE_PORT), req).getStatus()).isEqualTo(200);
    }

    @Test
    void neverGatesTheGsiConnector() throws Exception {
        // Dota posts to 127.0.0.1:3223 and cannot send a token; that connector must stay open.
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/gsi");
        req.setLocalPort(GSI_PORT);
        assertThat(run(new BridgeAuthFilter(TOKEN, BRIDGE_PORT), req).getStatus()).isEqualTo(200);
    }

    @Test
    void letsCorsPreflightThrough() throws Exception {
        assertThat(run(new BridgeAuthFilter(TOKEN, BRIDGE_PORT), onBridge("OPTIONS", "/settings")).getStatus())
                .isEqualTo(200);
    }

    @Test
    void disabledWhenNoTokenConfigured() throws Exception {
        // Blank token (dev / standalone jar / tests): enforcement is off, even with no token sent.
        assertThat(run(new BridgeAuthFilter("", BRIDGE_PORT), onBridge("GET", "/settings")).getStatus())
                .isEqualTo(200);
    }
}
