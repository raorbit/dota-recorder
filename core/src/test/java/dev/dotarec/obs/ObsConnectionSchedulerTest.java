package dev.dotarec.obs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dotarec.bridge.EventPublisher;
import io.obswebsocket.community.client.OBSRemoteController;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ObsConnectionSchedulerTest {

    /** The OBS process the last connection reached. */
    private static final ObsProcess OBS_RUN =
            new ObsProcess(4_242L, Instant.parse("2026-11-15T18:00:00Z"));

    private final ObsController obs = mock(ObsController.class);
    private final ObsHealth health = new ObsHealth();
    private final ObsDemand demand = mock(ObsDemand.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private boolean obsProcessRunning = true;
    private int processScans;
    private final ObsConnectionScheduler scheduler =
            new ObsConnectionScheduler(
                    obs,
                    health,
                    demand,
                    publisher,
                    () -> {
                        processScans++;
                        return obsProcessRunning;
                    });

    @Test
    void anObsThatExitedMidRecording_noLongerCountsAsRecording() {
        // OBS crashed mid-match (no OUTPUT_STOPPED), and its relaunch died before the websocket
        // came up, so no reconnect re-syncs the flag. Left set, it kept OBS wanted and the app busy
        // forever, and Electron never refilled the spent relaunch budget.
        health.setRecording(true);
        obsProcessRunning = false;

        scheduler.tryConnectAndConfigure();
        assertThat(health.isRecording()).isTrue(); // one miss is never enough
        scheduler.tryConnectAndConfigure();

        assertThat(health.isRecording()).isFalse();
    }

    @Test
    void anObsTheScanMissesOnceKeepsTheRecordingFlag() {
        // An elevated OBS can't be read by this process, so the scan misses it; its websocket blip
        // reconnects within a tick. Two misses in a row must come before the flag is cleared.
        health.setRecording(true);
        obsProcessRunning = false;
        scheduler.tryConnectAndConfigure(); // missed once
        obsProcessRunning = true;
        scheduler.tryConnectAndConfigure(); // seen: the count starts over
        obsProcessRunning = false;
        scheduler.tryConnectAndConfigure(); // missed once again

        assertThat(health.isRecording()).isTrue();
    }

    @Test
    void aDroppedSocketToAnObsThatStillRuns_keepsTheRecordingFlag() {
        // A websocket blip: OBS keeps recording, and the reconnect re-syncs the flag.
        health.setRecording(true);
        obsProcessRunning = true;

        scheduler.tryConnectAndConfigure();

        assertThat(health.isRecording()).isTrue();
    }

    @Test
    void anObsThatCrashedMidRecording_noLongerCountsAsRecording_whileItsRelaunchRuns() {
        // Electron relaunched the crashed OBS at once, but the relaunch's websocket isn't up yet.
        // The relaunch running says nothing about the crashed one's output: with its flag kept, a
        // finalize now takes the still-recording stop retry and saves the row without its file.
        health.setRecording(true);
        when(obs.connectedObsProcess()).thenReturn(OBS_RUN);
        when(obs.isObsRunning(OBS_RUN)).thenReturn(false);
        obsProcessRunning = true; // the relaunched OBS

        scheduler.tryConnectAndConfigure();
        assertThat(health.isRecording()).isTrue(); // one miss is never enough
        scheduler.tryConnectAndConfigure();

        assertThat(health.isRecording()).isFalse();
    }

    @Test
    void aDroppedSocketToTheNamedObs_keepsTheRecordingFlag_whileThatObsRuns() {
        health.setRecording(true);
        when(obs.connectedObsProcess()).thenReturn(OBS_RUN);
        when(obs.isObsRunning(OBS_RUN)).thenReturn(true);
        obsProcessRunning = false; // the named OBS decides, not a scan for any OBS

        scheduler.tryConnectAndConfigure();
        scheduler.tryConnectAndConfigure();

        assertThat(health.isRecording()).isTrue();
    }

    @Test
    void theProcessTableIsScannedOnlyForAFlagThatOutlivedTheConnection() {
        health.setConnected(true);
        health.setRecording(true);
        when(obs.ensureConnected()).thenReturn(true);
        scheduler.tryConnectAndConfigure(); // connected: the flag is OBS's own answer

        health.setConnected(false);
        health.setRecording(false);
        scheduler.tryConnectAndConfigure(); // nothing to clear

        assertThat(processScans).isZero();
    }

    @Test
    void configuresSceneOnConnectEdgeNotEveryTick() throws Exception {
        when(demand.isWanted()).thenReturn(true);
        when(obs.controller()).thenReturn(mock(OBSRemoteController.class));

        // Not connected yet: no scene setup.
        when(obs.ensureConnected()).thenReturn(false);
        scheduler.tryConnectAndConfigure();
        verify(obs, never()).configureScene();

        // First connect edge: configure exactly once.
        when(obs.ensureConnected()).thenReturn(true);
        scheduler.tryConnectAndConfigure();
        verify(obs, times(1)).configureScene();

        // Still connected: must NOT reconfigure on every tick.
        scheduler.tryConnectAndConfigure();
        verify(obs, times(1)).configureScene();

        // Drop, then reconnect: configure again on the new edge (self-heals a relaunched OBS).
        when(obs.ensureConnected()).thenReturn(false);
        scheduler.tryConnectAndConfigure();
        when(obs.ensureConnected()).thenReturn(true);
        scheduler.tryConnectAndConfigure();
        verify(obs, times(2)).configureScene();
    }

    @Test
    void retriesSceneSetupAfterAFailure() throws Exception {
        when(demand.isWanted()).thenReturn(true);
        when(obs.controller()).thenReturn(mock(OBSRemoteController.class));
        when(obs.ensureConnected()).thenReturn(true);
        // First attempt throws; the second must retry (the edge flag was reset on failure).
        doThrow(new ObsException("boom")).doNothing().when(obs).configureScene();

        scheduler.tryConnectAndConfigure(); // throws internally, caught, edge reset
        scheduler.tryConnectAndConfigure(); // retries and succeeds

        verify(obs, times(2)).configureScene();
    }

    @Test
    void doesNotTryToConnectToAnOnDemandObsThatIsNotWanted() {
        when(demand.isWanted()).thenReturn(false);

        scheduler.tryConnectAndConfigure();

        verify(obs, never()).ensureConnected();
        verify(publisher).publishStatus(); // the status heartbeat still goes out
    }

    @Test
    void keepsAConnectedObsConnectedWhileItIsNoLongerWanted() {
        // Electron stops an unwanted OBS after a grace period; until then the link stays healthy.
        when(demand.isWanted()).thenReturn(false);
        health.setConnected(true);
        when(obs.ensureConnected()).thenReturn(true);

        scheduler.tryConnectAndConfigure();

        verify(obs).ensureConnected();
    }
}
