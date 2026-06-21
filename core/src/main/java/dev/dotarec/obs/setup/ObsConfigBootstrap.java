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
 *
 * <p>On success it flips {@link ObsConfigReadiness} as its very last action, which unblocks
 * {@code GET /obs/launch-args} so Electron can launch OBS. On failure it leaves the flag unset, so
 * the endpoint keeps returning 409 rather than handing back args for a half-written config.
 */
@Component
public class ObsConfigBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ObsConfigBootstrap.class);

    private final ObsConfigWriter writer;
    private final ObsConfigReadiness readiness;

    public ObsConfigBootstrap(ObsConfigWriter writer, ObsConfigReadiness readiness) {
        this.writer = writer;
        this.readiness = readiness;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            writer.configure();
            // Mark readiness LAST, only after a successful configure(): launch args must never be
            // reported while the OBS config is half-written.
            readiness.markReady();
            log.info("OBS config bootstrap complete; launch args now available");
        } catch (RuntimeException e) {
            log.warn("OBS auto-config failed; recorder will report not ready: {}", e.toString());
            // Do NOT mark ready; GET /obs/launch-args stays 409 and the supervisor keeps retrying.
        }
    }
}
