package dev.dotarec.config;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.context.ApplicationListener;

/**
 * Pre-flight check that surfaces a clear, actionable message when the loopback
 * GSI ({@code 3223}) or bridge ({@code 3224}) port is already bound.
 *
 * <p>The whole pipeline is GSI-triggered and the UI status reflects GSI
 * connectivity, so a silent bind failure would look identical to "no Dota frames"
 * - an unactionable red. The most common cause is an orphaned core JVM from a
 * prior crash still holding the port. This runs as an early
 * {@link ApplicationStartingEvent} listener (before Tomcat binds) and only logs;
 * it does not abort. Tomcat remains the authority on the actual bind.
 */
public class PortGuard implements ApplicationListener<ApplicationStartingEvent> {

    private static final Logger log = LoggerFactory.getLogger(PortGuard.class);
    private static final String LOOPBACK = "127.0.0.1";

    // ApplicationStartingEvent fires before the Spring environment is available,
    // so @Value cannot be bound here; use the contract's fixed ports.
    @Value("${app.gsi.port:3223}")
    private int gsiPort = 3223;

    private static final int BRIDGE_PORT = 3224;

    @Override
    public void onApplicationEvent(ApplicationStartingEvent event) {
        checkPort(BRIDGE_PORT, "REST/WebSocket bridge");
        checkPort(gsiPort, "GSI ingest");
    }

    private void checkPort(int port, String role) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(false);
            probe.bind(new InetSocketAddress(InetAddress.getByName(LOOPBACK), port));
        } catch (IOException e) {
            log.error(
                    "Port {}:{} ({}) is already in use. Another Dota 2 Recorder "
                            + "instance (or an orphaned core process) may be running. "
                            + "Close it and relaunch.",
                    LOOPBACK, port, role);
        }
    }
}
