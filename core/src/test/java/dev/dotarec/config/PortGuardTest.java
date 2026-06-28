package dev.dotarec.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationStartingEvent;

/**
 * Drives {@link PortGuard#onApplicationEvent} with its {@code gsiPort} pointed at a port we control,
 * proving the guard is advisory-only: it logs an actionable "already in use" error when the GSI port
 * is held (so a silent bind failure stops looking identical to "no Dota frames"), and stays silent on
 * a free port (so a clean launch never spams a bogus "close it and relaunch"). Crucially it must
 * never throw — Tomcat stays the bind authority and an aborting guard would crash startup instead of
 * merely warning.
 */
class PortGuardTest {

    private Logger portGuardLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        portGuardLogger = (Logger) LoggerFactory.getLogger(PortGuard.class);
        appender = new ListAppender<>();
        appender.start();
        portGuardLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        portGuardLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void logsActionableError_andDoesNotThrow_whenGsiPortIsBound() throws Exception {
        // Bind a real loopback socket on an ephemeral port to simulate an orphaned core still
        // holding GSI ingest; setReuseAddress(false) mirrors the probe so the guard genuinely fails
        // to bind.
        try (ServerSocket held = new ServerSocket()) {
            held.setReuseAddress(false);
            held.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            int boundPort = held.getLocalPort();

            PortGuard guard = new PortGuard();
            setGsiPort(guard, boundPort);

            // Advisory-only: must return normally (Tomcat remains the bind authority). A throwing
            // guard would crash startup instead of surfacing a hint.
            assertThatCode(() -> guard.onApplicationEvent(startingEvent())).doesNotThrowAnyException();

            ILoggingEvent gsiError = singleGsiError(boundPort);
            String message = gsiError.getFormattedMessage();
            assertThat(message)
                    .contains("already in use")
                    .contains("orphaned core")
                    .contains("relaunch")
                    .contains(String.valueOf(boundPort));
        }
    }

    @Test
    void silent_whenGsiPortIsFree() throws Exception {
        // Open then immediately close to obtain a port number that is now free on 127.0.0.1.
        int freePort;
        try (ServerSocket scout = new ServerSocket()) {
            scout.setReuseAddress(false);
            scout.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            freePort = scout.getLocalPort();
        }

        PortGuard guard = new PortGuard();
        setGsiPort(guard, freePort);

        assertThatCode(() -> guard.onApplicationEvent(startingEvent())).doesNotThrowAnyException();

        // The guard also probes the fixed BRIDGE_PORT (3224), whose state is host-dependent; assert
        // only on the GSI-ingest role so the test stays deterministic regardless of :3224.
        assertThat(gsiErrors(freePort)).isEmpty();
    }

    /** Reflectively sets the private {@code gsiPort} field; there is no setter on the production class. */
    private static void setGsiPort(PortGuard guard, int port) throws Exception {
        Field field = PortGuard.class.getDeclaredField("gsiPort");
        field.setAccessible(true);
        field.setInt(guard, port);
    }

    private static ApplicationStartingEvent startingEvent() {
        return new ApplicationStartingEvent(
                new DefaultBootstrapContext(), new SpringApplication(), new String[0]);
    }

    /** ERROR events that mention the GSI-ingest role and the given port. */
    private List<ILoggingEvent> gsiErrors(int port) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .filter(e -> {
                    String m = e.getFormattedMessage();
                    return m.contains("GSI ingest") && m.contains(String.valueOf(port));
                })
                .toList();
    }

    private ILoggingEvent singleGsiError(int port) {
        List<ILoggingEvent> errors = gsiErrors(port);
        assertThat(errors).hasSize(1);
        return errors.get(0);
    }
}
