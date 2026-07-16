package dev.dotarec.obs;

import org.springframework.stereotype.Component;

/**
 * Tracks OBS connection/recording health, kept separate from GSI health.
 *
 * <p>Plan: live recording state includes "OBS recording status" distinct from "GSI connection
 * health"; both feed the UI status card over WebSocket. Splitting them lets the UI distinguish
 * "GSI ok but OBS not connected" from "OBS recording but GSI dropped". A green GSI card with OBS
 * disconnected must never silently record nothing, so {@link bridge.StatusService} surfaces these
 * three flags independently.
 *
 * <p>Thread-safety: the setters here are written from two different threads -- the
 * obs-websocket library's socket/lifecycle thread ({@link ObsController} connect/disconnect
 * callbacks and {@link ObsEvents} record-state callbacks) -- while the getters are read from
 * Spring MVC request threads ({@code GET /status}) and the WebSocket broadcast thread. Each flag
 * is an independent {@code volatile} boolean, which is exactly the right primitive: every write is
 * immediately visible to every reader, and there is no multi-field invariant to protect (the three
 * flags are reported as-is, never compared against each other under a lock).
 */
@Component
public class ObsHealth {

    private volatile boolean connected;
    private volatile boolean sceneActive;
    private volatile boolean recording;

    /**
     * Whether the last black-frame check found the game capture to be black (near-zero luminance)
     * while recording — the "OBS recording, but a black screen" failure that {@code isRecording}
     * alone can't distinguish. Set by {@link CaptureBlackFrameGuard} a few seconds into a recording
     * and cleared only when the NEXT recording arms — it deliberately survives the recording's end,
     * since the user is in fullscreen Dota while it records black and only sees the app afterwards.
     * Independent volatile like the others; the status layer reports it as-is.
     */
    private volatile boolean captureBlack;

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public boolean isSceneActive() {
        return sceneActive;
    }

    public void setSceneActive(boolean sceneActive) {
        this.sceneActive = sceneActive;
    }

    public boolean isRecording() {
        return recording;
    }

    public void setRecording(boolean recording) {
        this.recording = recording;
    }

    public boolean isCaptureBlack() {
        return captureBlack;
    }

    public void setCaptureBlack(boolean captureBlack) {
        this.captureBlack = captureBlack;
    }
}
