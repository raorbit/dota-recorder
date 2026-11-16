package dev.dotarec.obs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
    private CaptureBlackFrameGuard blackFrameGuard;
    private ObsEvents events;

    @BeforeEach
    void setUp() {
        health = new ObsHealth();
        blackFrameGuard = mock(CaptureBlackFrameGuard.class);
        events = new ObsEvents(health, blackFrameGuard);
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
        // An older obs-websocket sends STARTED with no outputPath (the bundled one names the file).
        // The recording must still be confirmed.
        events.onRecordStateChanged(ObsEvents.OUTPUT_STARTED, null);

        assertThat(health.isRecording()).isTrue();
        assertThat(events.lastStoppedOutputPath()).isNull();
    }

    @Test
    void started_keepsTheOutputPathItReports_untilTheNextStartOrAReset() {
        events.onRecordStateChanged(ObsEvents.OUTPUT_STARTED, "C:\\videos\\first.mkv");
        assertThat(events.startedOutputPath()).isEqualTo("C:\\videos\\first.mkv");

        // A STARTED that reports no path must not leave the previous recording's path behind.
        events.onRecordStateChanged(ObsEvents.OUTPUT_STARTED, null);
        assertThat(events.startedOutputPath()).isNull();

        events.onRecordStateChanged(ObsEvents.OUTPUT_STARTED, "C:\\videos\\second.mkv");
        events.reset();
        assertThat(events.startedOutputPath()).isNull();
    }

    @Test
    void stopped_clearsRecordingAndCapturesPath() {
        events.onRecordStateChanged(ObsEvents.OUTPUT_STARTED, null);

        events.onRecordStateChanged(ObsEvents.OUTPUT_STOPPED, "C:\\videos\\match.mkv");

        assertThat(health.isRecording()).isFalse();
        assertThat(events.lastStoppedOutputPath()).isEqualTo("C:\\videos\\match.mkv");
    }

    @Test
    void started_dropsTheStoppedPathOfTheRecordingBeforeIt() {
        // ObsController.startRecording resets BEFORE its corrective StopRecord of the previous
        // recording, so that stop's STOPPED lands after the reset. OBS stops the old output before
        // it starts the new one, and the new STARTED must disown the old file's path.
        events.onRecordStateChanged(ObsEvents.OUTPUT_STARTED, null);
        events.reset();
        events.onRecordStateChanged(ObsEvents.OUTPUT_STOPPED, "C:\\videos\\previous.mkv");
        events.onRecordStateChanged(ObsEvents.OUTPUT_STARTED, null);

        assertThat(events.lastStoppedOutputPath()).isNull();

        // A STOPPED after this recording's STARTED is this recording's own file.
        events.onRecordStateChanged(ObsEvents.OUTPUT_STOPPED, "C:\\videos\\current.mkv");
        assertThat(events.lastStoppedOutputPath()).isEqualTo("C:\\videos\\current.mkv");
    }

    @Test
    void started_armsTheBlackFrameCheck() {
        // Every confirmed start schedules the black-capture check, so a black recording can't stay silent.
        events.onRecordStateChanged(ObsEvents.OUTPUT_STARTED, null);

        verify(blackFrameGuard).armCheck();
    }

    @Test
    void stopped_keepsTheBlackCaptureWarning() {
        // The warning must OUTLIVE the recording: during a black match the user is in fullscreen
        // Dota and only sees the app after the recording ends. It clears on the next armCheck.
        health.setCaptureBlack(true);

        events.onRecordStateChanged(ObsEvents.OUTPUT_STOPPED, "C:\\videos\\match.mkv");

        assertThat(health.isCaptureBlack()).isTrue();
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

    @Test
    void awaitOutputIdle_withNoStopOutstanding_returnsWithoutBlocking() {
        // A first recording, or one that follows a long-finished stop, must not pay any wait (a long
        // timeout here would hang the test if it did).
        assertThat(events.awaitOutputIdle(60_000, 60_000)).isTrue();
    }

    @Test
    void awaitOutputIdle_holdsUntilStoppedArrives_thenSettles() throws Exception {
        events.expectStopped();
        long stoppedAfterMs = 150;
        long settleMs = 100;
        Thread obs =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(stoppedAfterMs);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            events.onRecordStateChanged(ObsEvents.OUTPUT_STOPPED, "C:\\videos\\match.mkv");
                        });

        long begin = System.nanoTime();
        obs.start();
        boolean idle = events.awaitOutputIdle(5_000, settleMs);
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;
        obs.join();

        assertThat(idle).isTrue();
        // Lower bound only: the wait cannot end before the event plus the settle.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(stoppedAfterMs + settleMs - 5);
    }

    @Test
    void awaitOutputIdle_returnsFalse_whenStoppedNeverArrives() {
        events.expectStopped();

        assertThat(events.awaitOutputIdle(50, 0)).isFalse();
    }

    @Test
    void cancelExpectedStop_dropsTheWait() {
        events.expectStopped();
        events.cancelExpectedStop();

        assertThat(events.awaitOutputIdle(60_000, 0)).isTrue();
    }

    @Test
    void reset_keepsAnArmedStopWait() {
        // startRecording() resets the per-recording state before it waits for the previous stop; if
        // reset dropped the wait, a back-to-back start would never hold off.
        events.expectStopped();
        events.reset();

        assertThat(events.awaitOutputIdle(50, 0)).isFalse();
    }

    @Test
    void awaitOutputIdle_settlesAfterAStopNobodyAskedFor() {
        // OBS ended the output on its own a moment ago: nothing is outstanding, but a start sent
        // right now would still land on top of the stop.
        events.onRecordStateChanged(ObsEvents.OUTPUT_STOPPED, "C:\\videos\\match.mkv");

        long begin = System.nanoTime();
        assertThat(events.awaitOutputIdle(60_000, 100)).isTrue();
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(90);
    }
}
