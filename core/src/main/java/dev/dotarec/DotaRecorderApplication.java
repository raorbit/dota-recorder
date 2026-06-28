package dev.dotarec;

import dev.dotarec.config.PortGuard;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Entry point for the Dota 2 Recorder core service.
 *
 * <p>The core is a launch-once, loopback-only Spring Boot sidecar supervised by
 * the Electron shell. It exposes a REST/WebSocket bridge on 127.0.0.1:3224 and a
 * dedicated GSI ingest connector on 127.0.0.1:3223 (see
 * {@link dev.dotarec.bridge.GsiConnectorConfig}).
 *
 * <p>Scheduling (the OBS connect/scene-setup retry loop, retention, enrichment, archiver) is enabled
 * by {@link dev.dotarec.config.SchedulingConfig}, gated behind {@code app.scheduling.enabled} so
 * tests can turn it off.
 */
@SpringBootApplication
public class DotaRecorderApplication {

    public static void main(String[] args) {
        // PortGuard listens for ApplicationStartingEvent, which fires before the Spring context (and
        // its bean post-processors) exist, so it must be registered as a listener here — a plain
        // @Component would be discovered far too late to see that event. Without this wiring its
        // actionable "port already in use / orphaned core" message would never fire.
        new SpringApplicationBuilder(DotaRecorderApplication.class)
                .listeners(new PortGuard())
                .run(args);
    }
}
