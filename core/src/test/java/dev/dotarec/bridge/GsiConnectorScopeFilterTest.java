package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link GsiConnectorScopeFilter}: the GSI connector serves only {@code /gsi}; any
 * other path on that port is 404, while requests on the bridge port pass through untouched.
 */
class GsiConnectorScopeFilterTest {

    private static final int BRIDGE_PORT = 3224;
    private static final int GSI_PORT = 3223;

    private static MockHttpServletResponse run(MockHttpServletRequest req)
            throws ServletException, IOException {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        new GsiConnectorScopeFilter(GSI_PORT).doFilter(req, resp, new MockFilterChain());
        return resp;
    }

    private static MockHttpServletRequest req(String method, String uri, int localPort) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setLocalPort(localPort);
        return r;
    }

    @Test
    void allowsGsiOnTheGsiPort() throws Exception {
        assertThat(run(req("POST", "/gsi", GSI_PORT)).getStatus()).isEqualTo(200);
    }

    @Test
    void blocksBridgeEndpointsOnTheGsiPort() throws Exception {
        assertThat(run(req("GET", "/settings", GSI_PORT)).getStatus()).isEqualTo(404);
        assertThat(run(req("GET", "/obs/launch-args", GSI_PORT)).getStatus()).isEqualTo(404);
    }

    @Test
    void leavesTheBridgePortUntouched() throws Exception {
        // Everything on the bridge connector passes through; auth is BridgeAuthFilter's job, not this.
        assertThat(run(req("GET", "/settings", BRIDGE_PORT)).getStatus()).isEqualTo(200);
        assertThat(run(req("POST", "/gsi", BRIDGE_PORT)).getStatus()).isEqualTo(200);
    }
}
