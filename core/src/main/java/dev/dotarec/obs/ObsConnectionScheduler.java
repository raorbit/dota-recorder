package dev.dotarec.obs;

import io.obswebsocket.community.client.OBSRemoteController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled background task that keeps OBS connected and the recorder scene configured.
 *
 * <p>Runs every 5s and, on each tick, calls {@link ObsController#ensureConnected()} (idempotent: a
 * no-op when already connected) and — on the first successful connect of this boot — configures the
 * scene via {@link ObsSceneConfigurer#ensureSceneReady}. Driving connect + scene setup from this
 * retry loop is what lets Electron simply spawn OBS and walk away: the core auto-connects and
 * configures as soon as OBS's websocket comes up, with no extra IPC or manual trigger after spawn.
 *
 * <p>A failed tick (OBS not yet reachable, a transient request error) only logs at debug and is
 * retried on the next tick; nothing here is fatal. The {@code controller()} accessor on
 * {@link ObsController} is package-private, which is exactly the in-package hook this scheduler
 * uses to hand the live controller to the configurer.
 */
@Component
public class ObsConnectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ObsConnectionScheduler.class);

    private final ObsController obsController;
    private final ObsSceneConfigurer sceneConfigurer;

    /**
     * Tracks the connection edge (only the scheduler thread touches it). Scene setup runs on each
     * disconnected->connected transition, not every tick: a freshly (re)launched OBS is configured
     * without an app restart, while a steady connection isn't reconfigured every 5s.
     */
    private boolean wasConnected = false;

    public ObsConnectionScheduler(
            ObsController obsController, ObsSceneConfigurer sceneConfigurer) {
        this.obsController = obsController;
        this.sceneConfigurer = sceneConfigurer;
    }

    @Scheduled(fixedRate = 5_000) // every 5 seconds
    public void tryConnectAndConfigure() {
        try {
            boolean connected = obsController.ensureConnected(); // idempotent; no-op if connected
            // Configure on the disconnected->connected EDGE. ensureSceneReady is idempotent, so
            // re-running it on a reconnect is a cheap no-op, but it also self-heals a relaunched OBS.
            if (connected && !wasConnected) {
                OBSRemoteController controller = obsController.controller();
                if (controller != null) {
                    sceneConfigurer.ensureSceneReady(controller);
                    log.info("OBS scene configured on connect");
                }
            }
            wasConnected = connected;
        } catch (Exception e) {
            log.debug("OBS connection/scene attempt failed (will retry): {}", e.toString());
            wasConnected = false; // force a re-configure on the next successful connect
        }
    }
}
