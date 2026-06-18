package dev.dotarec.obs;

import org.springframework.stereotype.Service;

/**
 * Drives OBS Studio recording over the obs-websocket protocol.
 *
 * <p>Plan (Recorder Controller): start/stop OBS, own buffer + output paths. The real impl will
 * connect to obs-websocket at 127.0.0.1:4455 (password-authenticated), call StartRecord /
 * StopRecord, and must wait for the OUTPUT_STARTED event before anchoring t=0 — that confirmed
 * instant becomes {@code RecordingSession.recordConfirmedWallMs}.
 *
 * <p>TODO(plan: Stack -> OBS control): add obs-websocket-java (NOT yet a dependency; referenced
 * here only as a comment) and implement connect/startRecord/stopRecord + event confirmation.
 */
@Service
public class ObsController {

    private final ObsHealth health;

    public ObsController(ObsHealth health) {
        this.health = health;
    }

    // TODO(plan): connect(127.0.0.1:4455, password); startRecord(); stopRecord();
    //             confirm OUTPUT_STARTED before anchoring; update ObsHealth.
}
