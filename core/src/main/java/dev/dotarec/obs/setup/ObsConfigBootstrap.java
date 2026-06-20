package dev.dotarec.obs.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs the OBS {@link ObsConfigWriter} once at startup.
 *
 * <p>An {@link ApplicationRunner} (invoked after the context is up, even under lazy init) is the
 * right hook: the writer must finish before the supervisor (PR3) launches OBS, since OBS reads the
 * websocket password/port once at launch. A config failure is logged, not fatal — the core still
 * serves the bridge, and the status card simply reports the recorder as not ready.
 */
@Component
public class ObsConfigBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ObsConfigBootstrap.class);

    private final ObsConfigWriter writer;

    public ObsConfigBootstrap(ObsConfigWriter writer) {
        this.writer = writer;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            writer.configure();
        } catch (RuntimeException e) {
            log.warn("OBS auto-config failed; recorder will report not ready: {}", e.toString());
        }
    }
}
