package dev.dotarec.obs.setup;

import org.springframework.stereotype.Component;

/**
 * Shared readiness flag for {@link ObsConfigBootstrap}.
 *
 * <p>The flag starts {@code false} and is flipped exactly once, as the very last action of a
 * successful {@link ObsConfigBootstrap#run}. It gates the {@code GET /obs/launch-args} endpoint:
 * Electron polls that endpoint and may only spawn OBS once the core has finished writing the OBS
 * config (password/port/scene-collection), since OBS reads those once at launch. A bootstrap
 * failure leaves the flag {@code false}, so the endpoint keeps returning 409 and the supervisor
 * keeps retrying rather than launching a misconfigured OBS.
 */
@Component
public class ObsConfigReadiness {

    private volatile boolean ready = false;

    /** Marks bootstrap complete; only called after {@code ObsConfigWriter.configure()} succeeds. */
    public void markReady() {
        this.ready = true;
    }

    /** Whether the OBS config bootstrap has completed and launch args are safe to report. */
    public boolean isReady() {
        return ready;
    }
}
