package dev.dotarec.bridge;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dotarec.obs.ObsController;
import dev.dotarec.obs.ObsSceneConfigurer;
import io.obswebsocket.community.client.OBSRemoteController;
import io.obswebsocket.community.client.message.response.sources.GetSourceScreenshotResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live preview of the OBS "Dota" scene for the recording settings UI, over the loopback bridge.
 *
 * <p>obs-websocket v5 has no video stream, so the preview is a periodically-polled screenshot: the
 * renderer fetches {@code GET /obs/preview} (~1s while the tab is visible) and renders the returned
 * data URI into an {@code <img>}. The data URI form (not a raw image body) is deliberate: an
 * {@code <img src>} request cannot carry the {@code X-Dotarec-Token} header that
 * {@link BridgeAuthFilter} requires on every bridge path, so the authed JSON fetch returns the
 * base64 and the {@code <img>} just renders it.
 *
 * <p>Degrades gracefully: the endpoint ALWAYS returns HTTP 200 with {@code dataUri == null} on every
 * failure path (OBS down, not connected, no/unsuccessful response, exception). A BLACK frame when
 * Dota isn't live is still a valid (non-null) data URI and is rendered as-is — null means a
 * connection/request failure, not an idle scene. {@link BridgeAuthFilter} already covers this path;
 * no filter change.
 */
@RestController
public class PreviewController {

    private static final Logger log = LoggerFactory.getLogger(PreviewController.class);

    /** A scene is a valid screenshot source; reuse the canonical scene name so a rename can't drift. */
    private static final String SCENE_NAME = ObsSceneConfigurer.SCENE_NAME;
    private static final String IMAGE_FORMAT = "jpg";
    /** 16:9 preview; OBS scales the scene into it. Width/height range 8..4096 per the v5 spec. */
    private static final int PREVIEW_WIDTH = 480;
    private static final int PREVIEW_HEIGHT = 270;
    /** -1 lets OBS pick the codec default quality. */
    private static final int COMPRESSION_DEFAULT = -1;
    private static final long REQUEST_TIMEOUT_MS = 5_000L;

    private final ObsController obsController;

    public PreviewController(ObsController obsController) {
        this.obsController = obsController;
    }

    @GetMapping("/obs/preview")
    public ScenePreview preview() {
        // OBS down / unreachable: degrade to a null frame so the UI shows its placeholder.
        if (!obsController.ensureConnected()) {
            return new ScenePreview(null);
        }
        OBSRemoteController c = obsController.connectedController();
        if (c == null) {
            return new ScenePreview(null);
        }
        try {
            GetSourceScreenshotResponse resp =
                    c.getSourceScreenshot(
                            SCENE_NAME,
                            IMAGE_FORMAT,
                            PREVIEW_WIDTH,
                            PREVIEW_HEIGHT,
                            COMPRESSION_DEFAULT,
                            REQUEST_TIMEOUT_MS);
            if (resp == null || !resp.isSuccessful() || resp.getImageData() == null) {
                return new ScenePreview(null);
            }
            // getImageData() is already a full "data:image/jpeg;base64,..." URI — drop straight into
            // <img src>; do NOT prepend a data: prefix.
            return new ScenePreview(resp.getImageData());
        } catch (Exception e) {
            log.debug("OBS scene screenshot failed (OBS busy/down?): {}", e.toString());
            return new ScenePreview(null);
        }
    }

    /**
     * Preview frame for the settings UI. {@code dataUri} is a ready-to-use
     * {@code data:image/jpeg;base64,...} URI on success, or {@code null} on any degrade path.
     * {@code @JsonInclude(ALWAYS)} so a null serializes as {@code "dataUri":null} (stable shape).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ScenePreview(String dataUri) {}
}
