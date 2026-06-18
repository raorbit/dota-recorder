package dev.dotarec.obs;

import org.springframework.stereotype.Component;

/**
 * Tracks OBS connection/recording health, kept separate from GSI health.
 *
 * <p>Plan: live recording state includes "OBS recording status" distinct from "GSI connection
 * health"; both feed the UI status card over WebSocket. Splitting them lets the UI distinguish
 * "GSI ok but OBS not connected" from "OBS recording but GSI dropped".
 *
 * <p>TODO(plan): set by {@code ObsController} on connect / scene change / record start-stop.
 */
@Component
public class ObsHealth {

    private volatile boolean connected;
    private volatile boolean sceneActive;
    private volatile boolean recording;

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
}
