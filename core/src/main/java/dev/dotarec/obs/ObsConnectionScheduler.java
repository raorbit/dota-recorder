package dev.dotarec.obs;

import dev.dotarec.bridge.EventPublisher;
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
 * retried on the next tick; nothing here is fatal. Scene setup runs through the package-private
 * {@link ObsController#configureScene()}, which also decides whether the audio captures belong in it.
 */
@Component
public class ObsConnectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ObsConnectionScheduler.class);

    private final ObsController obsController;
    private final EventPublisher eventPublisher;

    /**
     * Tracks the connection edge (only the scheduler thread touches it). Scene setup runs on each
     * disconnected->connected transition, not every tick: a freshly (re)launched OBS is configured
     * without an app restart, while a steady connection isn't reconfigured every 5s.
     */
    private boolean wasConnected = false;

    /** Logs a scene-config failure loudly once, then quietly, so a persistent failure is visible but
     * the 5s retry doesn't spam. Reset after a clean configure so a later regression re-surfaces. */
    private boolean loggedConfigError = false;

    public ObsConnectionScheduler(
            ObsController obsController,
            EventPublisher eventPublisher) {
        this.obsController = obsController;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedRate = 5_000) // every 5 seconds
    public void tryConnectAndConfigure() {
        try {
            boolean connected = obsController.ensureConnected(); // idempotent; no-op if connected
            // Configure on the disconnected->connected EDGE. ensureSceneReady is idempotent, so
            // re-running it on a reconnect is a cheap no-op, but it also self-heals a relaunched OBS.
            if (connected && !wasConnected) {
                if (obsController.controller() != null) {
                    obsController.configureScene();
                    log.info("OBS scene configured on connect");
                    loggedConfigError = false;
                }
            }
            wasConnected = connected;
        } catch (Exception e) {
            // Surface the first failure with its stack (a silently-looping scene config is a real
            // bug), then drop to debug so the 5s retry doesn't spam.
            if (!loggedConfigError) {
                log.warn("OBS scene configuration failed; will keep retrying", e);
                loggedConfigError = true;
            } else {
                log.debug("OBS connection/scene attempt failed (will retry): {}", e.toString());
            }
            wasConnected = false; // force a re-configure on the next successful connect
        } finally {
            // Heartbeat the live status to the renderer every tick. OBS connect/disconnect only flips
            // ObsHealth; nothing else broadcasts a status frame, so without this the UI stays stuck on
            // its initial connect-time frame and shows a stale "error" after OBS reconnects. Best-effort:
            // a broadcast hiccup must never break the connect loop.
            try {
                eventPublisher.publishStatus();
            } catch (RuntimeException ignored) {
                /* status heartbeat is best-effort */
            }
        }
    }
}
