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

    @Test
    void awaitRecordConfirmed_returnsInstant_whenStartedArrives() {
        events.clearRecordConfirmation();
        Instant before = Instant.now();

        events.onRecordStateChanged(ObsEvents.OUTPUT_STARTED, null);
        Instant confirmed = events.awaitRecordConfirmed(1_000);

        assertThat(confirmed).isNotNull().isAfterOrEqualTo(before);
        assertThat(confirmed).isEqualTo(events.recordConfirmedAt());
    }

    @Test
    void awaitRecordConfirmed_returnsNull_whenStartedNeverArrives() {
        events.clearRecordConfirmation();

        // No OUTPUT_STARTED before the (short) timeout: the caller must learn the start was not
        // confirmed so it can abort the phantom/black recording.
        assertThat(events.awaitRecordConfirmed(50)).isNull();
    }

    @Test
    void clearRecordConfirmation_reArmsForASecondBackToBackRecording() {
        // First recording confirms.
        events.clearRecordConfirmation();
        events.onRecordStateChanged(ObsEvents.OUTPUT_STARTED, null);
        Instant first = events.awaitRecordConfirmed(1_000);
        assertThat(first).isNotNull();

        // Arming the next recording drops the prior instant, so a back-to-back start cannot read the
        // first match's confirmation -- the cross-match anchor-leak regression this whole change fixes.
        events.clearRecordConfirmation();
        assertThat(events.recordConfirmedAt()).as("clear drops the prior confirmed instant").isNull();

        events.onRecordStateChanged(ObsEvents.OUTPUT_STARTED, null);
        Instant second = events.awaitRecordConfirmed(1_000);
        assertThat(second).isNotNull().isAfterOrEqualTo(first);
    }

    @Test
    void awaitRecordConfirmed_withNoLatchArmed_returnsCurrentInstantWithoutBlocking() {
        // No clearRecordConfirmation(): await must not block on a missing latch -- it returns whatever
        // is currently known immediately (a long timeout here would hang the test if it did block).
        events.onRecordStateChanged(ObsEvents.OUTPUT_STARTED, null);
        assertThat(events.awaitRecordConfirmed(60_000)).isEqualTo(events.recordConfirmedAt());
    }

    @Test
    void reset_clearsLatchSoAwaitDoesNotBlock() {
        events.clearRecordConfirmation();
        events.reset();

        // reset() drops the armed latch; a subsequent await returns immediately (null) rather than
        // hanging the full timeout on a recording that will never be confirmed.
        assertThat(events.awaitRecordConfirmed(60_000)).isNull();
    }
}
