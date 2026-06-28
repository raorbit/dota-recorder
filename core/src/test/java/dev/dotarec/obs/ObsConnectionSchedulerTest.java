package dev.dotarec.obs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
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

    @Test
    void configuresSceneOnConnectEdgeNotEveryTick() throws Exception {
        ObsController obs = mock(ObsController.class);
        ObsSceneConfigurer cfg = mock(ObsSceneConfigurer.class);
        OBSRemoteController ctrl = mock(OBSRemoteController.class);
        when(obs.controller()).thenReturn(ctrl);
        ObsConnectionScheduler scheduler = new ObsConnectionScheduler(obs, cfg, mock(EventPublisher.class));

        // Not connected yet: no scene setup.
        when(obs.ensureConnected()).thenReturn(false);
        scheduler.tryConnectAndConfigure();
        verify(cfg, never()).ensureSceneReady(any());

        // First connect edge: configure exactly once.
        when(obs.ensureConnected()).thenReturn(true);
        scheduler.tryConnectAndConfigure();
        verify(cfg, times(1)).ensureSceneReady(ctrl);

        // Still connected: must NOT reconfigure on every tick.
        scheduler.tryConnectAndConfigure();
        verify(cfg, times(1)).ensureSceneReady(ctrl);

        // Drop, then reconnect: configure again on the new edge (self-heals a relaunched OBS).
        when(obs.ensureConnected()).thenReturn(false);
        scheduler.tryConnectAndConfigure();
        when(obs.ensureConnected()).thenReturn(true);
        scheduler.tryConnectAndConfigure();
        verify(cfg, times(2)).ensureSceneReady(ctrl);
    }

    @Test
    void retriesSceneSetupAfterAFailure() throws Exception {
        ObsController obs = mock(ObsController.class);
        ObsSceneConfigurer cfg = mock(ObsSceneConfigurer.class);
        OBSRemoteController ctrl = mock(OBSRemoteController.class);
        when(obs.controller()).thenReturn(ctrl);
        when(obs.ensureConnected()).thenReturn(true);
        // First attempt throws; the second must retry (the edge flag was reset on failure).
        doThrow(new ObsException("boom")).doNothing().when(cfg).ensureSceneReady(ctrl);

        ObsConnectionScheduler scheduler = new ObsConnectionScheduler(obs, cfg, mock(EventPublisher.class));
        scheduler.tryConnectAndConfigure(); // throws internally, caught, edge reset
        scheduler.tryConnectAndConfigure(); // retries and succeeds

        verify(cfg, times(2)).ensureSceneReady(ctrl);
    }
}
