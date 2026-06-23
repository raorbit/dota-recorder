package dev.dotarec.obs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the parts of {@link ObsController} that don't need a live OBS websocket.
 *
 * <p>Focus: the per-recording anchor lifecycle. {@link ObsEvents#recordConfirmedAt()} is sticky --
 * OUTPUT_STOPPED does NOT clear it, only {@link ObsEvents#reset()} does, and reset() otherwise only
 * runs on (re)connect. So {@link ObsController#startRecording()} must reset it up front, or the FSM
 * (which reads the anchor right after StartRecord returns, before this match's OUTPUT_STARTED lands)
 * would anchor a second match on the first match's start instant.
 */
class ObsControllerTest {

    @Test
    void startRecording_resetsStalePerRecordingAnchorBeforeStarting() {
        ObsHealth health = new ObsHealth();
        ObsEvents events = new ObsEvents(health);
        // Simulate a PRIOR match: started, then stopped. OUTPUT_STOPPED leaves recordConfirmedAt set
        // -- exactly the sticky value that would otherwise become a second match's anchor.
        events.onRecordStateChanged(ObsEvents.OUTPUT_STARTED, null);
        events.onRecordStateChanged(ObsEvents.OUTPUT_STOPPED, "C:\\videos\\match1.mkv");
        assertThat(events.recordConfirmedAt())
                .as("anchor is sticky across STOPPED -- this is why startRecording must reset it")
                .isNotNull();

        // Not connected, so the StartRecord itself fails; but startRecording must FIRST clear the
        // stale anchor so the FSM's read of recordConfirmedAt() falls back to the live frame time.
        ObsController controller = new ObsController(null, health, events);
        assertThatThrownBy(controller::startRecording).isInstanceOf(ObsException.class);

        assertThat(events.recordConfirmedAt())
                .as("startRecording must reset the previous match's confirmed-start anchor")
                .isNull();
    }
}
