package dev.dotarec.obs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ObsEvents} -- the part of OBS control that is testable without a live OBS.
 *
 * <p>These lock in the load-bearing v5 behaviors: recording is "real" only on OUTPUT_STARTED (and
 * the confirmed wall instant is captured then), the output path is captured from STOPPED, and
 * intermediate states are inert. Health flags are driven through a real {@link ObsHealth} so the
 * connect-state propagation that {@code StatusService} reads is exercised too.
 */
class ObsEventsTest {

    private ObsHealth health;
    private ObsEvents events;

    @BeforeEach
    void setUp() {
        health = new ObsHealth();
        events = new ObsEvents(health);
    }

    @Test
    void started_marksRecordingAndCapturesConfirmedInstant() {
        Instant before = Instant.now();

        events.onRecordStateChanged(ObsEvents.OUTPUT_STARTED, null);

        assertThat(health.isRecording()).isTrue();
        assertThat(events.recordConfirmedAt())
                .as("OUTPUT_STARTED must record the confirmed wall-clock anchor")
                .isNotNull()
                .isAfterOrEqualTo(before);
    }

    @Test
    void started_doesNotDependOnEventOutputPath() {
        // v5 regression: the STARTED event's outputPath is null. We must still confirm recording.
        events.onRecordStateChanged(ObsEvents.OUTPUT_STARTED, null);

        assertThat(health.isRecording()).isTrue();
        assertThat(events.lastStoppedOutputPath()).isNull();
    }

    @Test
    void stopped_clearsRecordingAndCapturesPath() {
        events.onRecordStateChanged(ObsEvents.OUTPUT_STARTED, null);

        events.onRecordStateChanged(ObsEvents.OUTPUT_STOPPED, "C:\\videos\\match.mkv");

        assertThat(health.isRecording()).isFalse();
        assertThat(events.lastStoppedOutputPath()).isEqualTo("C:\\videos\\match.mkv");
    }

    @Test
    void stopped_withBlankPath_doesNotOverwriteState() {
        events.onRecordStateChanged(ObsEvents.OUTPUT_STOPPED, "   ");

        assertThat(health.isRecording()).isFalse();
        assertThat(events.lastStoppedOutputPath()).isNull();
    }

    @Test
    void intermediateAndNullStates_areInert() {
        events.onRecordStateChanged("OBS_WEBSOCKET_OUTPUT_STARTING", null);
        events.onRecordStateChanged("OBS_WEBSOCKET_OUTPUT_RECONNECTING", null);
        events.onRecordStateChanged(null, null);

        assertThat(health.isRecording()).isFalse();
        assertThat(events.recordConfirmedAt()).isNull();
        assertThat(events.lastStoppedOutputPath()).isNull();
    }

    @Test
    void reset_clearsPerRecordingState() {
        events.onRecordStateChanged(ObsEvents.OUTPUT_STARTED, null);
        events.onRecordStateChanged(ObsEvents.OUTPUT_STOPPED, "C:\\videos\\match.mkv");

        events.reset();

        assertThat(events.recordConfirmedAt()).isNull();
        assertThat(events.lastStoppedOutputPath()).isNull();
    }
}
