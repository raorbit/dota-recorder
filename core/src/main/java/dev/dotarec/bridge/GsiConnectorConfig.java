package dev.dotarec.bridge;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;

/**
 * Adds a second Tomcat connector dedicated to Dota Game State Integration POSTs.
 *
 * <p>The primary connector (127.0.0.1:3224, configured via {@code server.port}/
 * {@code server.address}) serves the REST + WebSocket bridge the Electron shell
 * uses. Dota GSI traffic is isolated onto its own connector on 127.0.0.1:3223 so
 * the two surfaces can evolve independently. Both bind to loopback only: this is
 * the correct local-only security posture and it sidesteps the Windows Defender
 * Firewall prompt that an all-interfaces bind would trigger.
 */
@Configuration
public class GsiConnectorConfig
        implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private static final String LOOPBACK = "127.0.0.1";

    /**
     * Thread cap for the GSI connector. Dota posts its frames one at a time from a single process,
     * so Tomcat's defaults (200 max, 10 kept idle) are pure overhead here. Spring's
     * {@code server.tomcat.threads.*} only reaches the primary connector; an additional connector
     * keeps its own executor, so it is capped directly. Package-visible for the connector test.
     */
    static final int MAX_THREADS = 4;

    static final int MIN_SPARE_THREADS = 1;

    private final int gsiPort;

    public GsiConnectorConfig(@Value("${app.gsi.port:3223}") int gsiPort) {
        this.gsiPort = gsiPort;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addAdditionalTomcatConnectors(gsiConnector());
    }

    private Connector gsiConnector() {
        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setPort(gsiPort);
        connector.setScheme("http");
        // Bind this connector to loopback only. setProperty("address", ...)
        // delegates to the HTTP protocol handler's bind address.
        connector.setProperty("address", LOOPBACK);
        connector.setProperty("maxThreads", Integer.toString(MAX_THREADS));
        connector.setProperty("minSpareThreads", Integer.toString(MIN_SPARE_THREADS));
        return connector;
    }
}
