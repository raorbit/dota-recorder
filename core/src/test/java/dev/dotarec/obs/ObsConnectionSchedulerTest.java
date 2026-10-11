package dev.dotarec.obs;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dotarec.bridge.EventPublisher;
import io.obswebsocket.community.client.OBSRemoteController;
import org.junit.jupiter.api.Test;

class ObsConnectionSchedulerTest {

    private final ObsController obs = mock(ObsController.class);
    private final ObsHealth health = new ObsHealth();
    private final ObsDemand demand = mock(ObsDemand.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final ObsConnectionScheduler scheduler =
            new ObsConnectionScheduler(obs, health, demand, publisher);

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
