package dev.dotarec.fsm;

/**
 * Mutable holder for the in-flight recording, owned by the FSM while in RECORDING/STOPPING.
 *
 * <p>{@code recordConfirmedWallMs} is the local wall-clock instant OBS confirmed OUTPUT_STARTED;
 * it is the anchor every {@code markers.video_offset_s} is computed against (plan + see
 * {@code VideoOffsetCalculator}). {@code surrogateId} keys the in-progress recording before the
 * official Dota match_id is known/confirmed by enrichment.
 *
 * <p>TODO(plan): populate on START; persist into the {@code matches} row on finalize.
 */
public class RecordingSession {

    private String surrogateId;
    private long recordConfirmedWallMs;
    private String videoPath;
    private String scene;

    public String getSurrogateId() {
        return surrogateId;
    }

    public void setSurrogateId(String surrogateId) {
        this.surrogateId = surrogateId;
    }

    public long getRecordConfirmedWallMs() {
        return recordConfirmedWallMs;
    }

    public void setRecordConfirmedWallMs(long recordConfirmedWallMs) {
        this.recordConfirmedWallMs = recordConfirmedWallMs;
    }

    public String getVideoPath() {
        return videoPath;
    }

    public void setVideoPath(String videoPath) {
        this.videoPath = videoPath;
    }

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }
}
