package dev.dotarec;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Dota 2 Recorder core service.
 *
 * <p>The core is a launch-once, loopback-only Spring Boot sidecar supervised by
 * the Electron shell. It exposes a REST/WebSocket bridge on 127.0.0.1:3224 and a
 * dedicated GSI ingest connector on 127.0.0.1:3223 (see
 * {@link dev.dotarec.bridge.GsiConnectorConfig}).
 */
@SpringBootApplication
public class DotaRecorderApplication {

    public static void main(String[] args) {
        SpringApplication.run(DotaRecorderApplication.class, args);
    }
}
