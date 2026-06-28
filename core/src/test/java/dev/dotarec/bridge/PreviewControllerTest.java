package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.dotarec.bridge.PreviewController.ScenePreview;
import dev.dotarec.obs.ObsController;
import dev.dotarec.obs.ObsSceneConfigurer;
import io.obswebsocket.community.client.OBSRemoteController;
import io.obswebsocket.community.client.message.response.sources.GetSourceScreenshotResponse;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PreviewController}. The endpoint must ALWAYS return a {@link ScenePreview}
 * (never throw / 500): {@code dataUri} is non-null only on a successful screenshot and null on every
 * degrade path (OBS down, not connected, null/unsuccessful response, null image data, exception).
 * Wired against a mocked {@link ObsController} so no live OBS is needed.
 */
class PreviewControllerTest {

    private GetSourceScreenshotResponse screenshot(OBSRemoteController c) {
        GetSourceScreenshotResponse resp = mock(GetSourceScreenshotResponse.class);
        when(c.getSourceScreenshot(
                        eq(ObsSceneConfigurer.SCENE_NAME), eq("jpg"), any(), any(), any(), anyLong()))
                .thenReturn(resp);
        return resp;
    }

    @Test
    void obsDown_returnsNullDataUri() {
        ObsController obs = mock(ObsController.class);
        when(obs.ensureConnected()).thenReturn(false);

        assertThat(new PreviewController(obs).preview().dataUri()).isNull();
    }

    @Test
    void connectedControllerNull_returnsNullDataUri() {
        ObsController obs = mock(ObsController.class);
        when(obs.ensureConnected()).thenReturn(true);
        when(obs.connectedController()).thenReturn(null);

        assertThat(new PreviewController(obs).preview().dataUri()).isNull();
    }

    @Test
    void successfulScreenshot_returnsDataUri() {
        ObsController obs = mock(ObsController.class);
        OBSRemoteController c = mock(OBSRemoteController.class);
        when(obs.ensureConnected()).thenReturn(true);
        when(obs.connectedController()).thenReturn(c);
        GetSourceScreenshotResponse resp = screenshot(c);
        when(resp.isSuccessful()).thenReturn(true);
        when(resp.getImageData()).thenReturn("data:image/jpeg;base64,AAAA");

        assertThat(new PreviewController(obs).preview().dataUri())
                .isEqualTo("data:image/jpeg;base64,AAAA");
    }

    @Test
    void unsuccessfulResponse_returnsNullDataUri() {
        ObsController obs = mock(ObsController.class);
        OBSRemoteController c = mock(OBSRemoteController.class);
        when(obs.ensureConnected()).thenReturn(true);
        when(obs.connectedController()).thenReturn(c);
        GetSourceScreenshotResponse resp = screenshot(c);
        when(resp.isSuccessful()).thenReturn(false);

        assertThat(new PreviewController(obs).preview().dataUri()).isNull();
    }

    @Test
    void nullImageData_returnsNullDataUri() {
        ObsController obs = mock(ObsController.class);
        OBSRemoteController c = mock(OBSRemoteController.class);
        when(obs.ensureConnected()).thenReturn(true);
        when(obs.connectedController()).thenReturn(c);
        GetSourceScreenshotResponse resp = screenshot(c);
        when(resp.isSuccessful()).thenReturn(true);
        when(resp.getImageData()).thenReturn(null);

        assertThat(new PreviewController(obs).preview().dataUri()).isNull();
    }

    @Test
    void screenshotThrows_returnsNullDataUri() {
        ObsController obs = mock(ObsController.class);
        OBSRemoteController c = mock(OBSRemoteController.class);
        when(obs.ensureConnected()).thenReturn(true);
        when(obs.connectedController()).thenReturn(c);
        when(c.getSourceScreenshot(
                        eq(ObsSceneConfigurer.SCENE_NAME), eq("jpg"), any(), any(), any(), anyLong()))
                .thenThrow(new RuntimeException("boom"));

        // The endpoint swallows the exception and degrades; it must not propagate / 500.
        assertThat(new PreviewController(obs).preview().dataUri()).isNull();
    }
}
