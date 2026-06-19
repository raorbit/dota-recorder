package dev.dotarec.obs;

import java.nio.file.Path;

/**
 * Testable seam for grabbing a match thumbnail, mirroring the {@link ObsRecorder} seam pattern.
 *
 * <p>The FSM depends on this interface (not the concrete {@link ThumbnailService}) so its finalize
 * sequence can be unit-tested against an in-memory fake without a live OBS. {@link ThumbnailService}
 * is the production implementation backed by obs-websocket's SaveSourceScreenshot.
 *
 * <p>Caller contract (unchanged from {@link ThumbnailService}): invoke BEFORE
 * {@link ObsRecorder#stopRecording()} -- a screenshot after the scene goes idle comes back black.
 */
public interface ThumbnailCapturer {

    /**
     * Captures a thumbnail for the given id while the scene is still live.
     *
     * @param id identifier used for the file name (recording surrogate id or match id)
     * @return the absolute path the thumbnail was written to
     * @throws ObsException if OBS is not connected, has no active scene, or the save fails
     */
    Path captureCurrentScene(String id);
}
