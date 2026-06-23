package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Boots the REAL embedded Tomcat with BOTH connectors -- the bridge (a random {@code server.port})
 * and the GSI connector ({@code app.gsi.port}, added by {@link GsiConnectorConfig}) -- plus the real
 * {@link BridgeAuthFilter} and {@link GsiConnectorScopeFilter}, and drives them over actual HTTP.
 * This is the empirical proof for two posture claims that were otherwise only reasoned from code:
 *
 * <ul>
 *   <li><b>The bypass was real.</b> The GSI connector shares ONE servlet context with the bridge, so
 *       app endpoints are genuinely routed on the GSI port. {@code /probe} has a handler (proven
 *       reachable on the bridge port with a token), yet on the GSI port it returns 404 -- which can
 *       only be the scope filter actively blocking a routable request. Absent that filter, the GSI
 *       port would have served it: that is exactly the original bypass, now closed.</li>
 *   <li><b>The token gates the bridge.</b> The same {@code /probe} handler is 401 without the token
 *       and 200 with it; the GSI connector stays confined to {@code /gsi} regardless of the token.</li>
 * </ul>
 *
 * <p>Stand-in controllers are used so the proof is about connector + filter WIRING, not any real
 * controller's logic; the two connectors and both filters are the production beans.
 */
@SpringBootTest(
        classes = BridgeConnectorIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BridgeConnectorIntegrationTest {

    private static final String TOKEN = "integration-token";
    private static int gsiPort;

    @LocalServerPort private int bridgePort;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        gsiPort = TestSocketUtils.findAvailableTcpPort();
        registry.add("app.gsi.port", () -> gsiPort);
        registry.add("app.bridge.token", () -> TOKEN);
    }

    private final HttpClient http = HttpClient.newHttpClient();

    private int status(int port, String method, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if ("POST".equals(method)) {
            b.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            b.GET();
        }
        if (token != null) {
            b.header(BridgeAuthFilter.TOKEN_HEADER, token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void gsiConnectorServesOnlyGsi() throws Exception {
        // /gsi is reachable on the GSI port without a token: the connector routes app endpoints
        // (proving it shares the servlet context), and GSI ingest stays open for Dota.
        assertThat(status(gsiPort, "POST", "/gsi", null)).isEqualTo(200);
        // A handler that DOES exist is 404 on the GSI port -> the scope filter isolates it (the bypass,
        // now closed), and it does so independent of the token.
        assertThat(status(gsiPort, "GET", "/probe", null)).isEqualTo(404);
        assertThat(status(gsiPort, "GET", "/probe", TOKEN)).isEqualTo(404);
    }

    @Test
    void bridgeConnectorRequiresTheToken() throws Exception {
        // Same /probe handler: blocked without the token, served with it -> the token gates the bridge.
        assertThat(status(bridgePort, "GET", "/probe", null)).isEqualTo(401);
        assertThat(status(bridgePort, "GET", "/probe", TOKEN)).isEqualTo(200);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({
        GsiConnectorConfig.class,
        BridgeAuthFilter.class,
        GsiConnectorScopeFilter.class,
        BridgeConnectorIntegrationTest.ProbeController.class
    })
    static class TestApp {}

    /** Stand-in for the real controllers: a bridge endpoint ({@code /probe}) and GSI ingest. */
    @RestController
    static class ProbeController {
        @GetMapping("/probe")
        String probe() {
            return "ok";
        }

        @PostMapping("/gsi")
        String gsi() {
            return "ok";
        }
    }
}
