package dev.dotarec.bridge;

import com.google.gson.JsonObject;
import dev.dotarec.obs.ObsController;
import io.obswebsocket.community.client.OBSRemoteController;
import io.obswebsocket.community.client.message.response.inputs.CreateInputResponse;
import io.obswebsocket.community.client.message.response.inputs.GetInputPropertiesListPropertyItemsResponse;
import io.obswebsocket.community.client.model.Input.PropertyItem;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Audio device/process enumeration for the settings UI, over the loopback bridge.
 *
 * <p>{@code GET /audio/inputs?kind=application|output|input} returns the selectable values for one
 * audio source kind as a list of {@link AudioInputOption} ({@code value} goes into
 * {@code AudioSource.target}; {@code label} is the picker display name).
 *
 * <p>obs-websocket v5 has no request to list a property's values without a live input, so this
 * endpoint creates a transient hidden probe input of the matching kind, enumerates its device/window
 * property, then removes it in a {@code finally}. When OBS is not connected or enumeration fails it
 * degrades to an empty list with HTTP 200 (never 500), so the settings UI stays usable while OBS is
 * down. {@link BridgeAuthFilter} already covers this path; no filter change.
 */
@RestController
public class AudioController {

    private static final Logger log = LoggerFactory.getLogger(AudioController.class);

    private static final String SCENE_NAME = "Dota";
    /**
     * Prefix for the hidden helper input each enumeration call creates and removes. A fresh UUID is
     * appended per call so concurrent enumerations (the settings UI primes application/output/input in
     * parallel at mount) never collide on one input name — which would make a created probe fail and a
     * sibling's finally remove another call's probe mid-enumerate.
     */
    private static final String PROBE_PREFIX = "__dotarec_probe_audio_";
    private static final long REQUEST_TIMEOUT_MS = 5_000L;

    private final ObsController obsController;

    public AudioController(ObsController obsController) {
        this.obsController = obsController;
    }

    @GetMapping("/audio/inputs")
    public List<AudioInputOption> inputs(@RequestParam String kind) {
        String obsKind = obsKind(kind);
        String propertyName = propertyName(kind);
        if (obsKind == null || propertyName == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "kind must be one of application|output|input, got: " + kind);
        }
        // OBS down / unreachable: degrade gracefully so the picker just shows no options.
        if (!obsController.ensureConnected()) {
            return List.of();
        }
        OBSRemoteController c = obsController.connectedController();
        if (c == null) {
            return List.of();
        }
        try {
            return enumerate(c, obsKind, propertyName);
        } catch (Exception e) {
            log.debug("Audio input enumeration for kind '{}' failed: {}", kind, e.toString());
            return List.of();
        }
    }

    /**
     * Creates the transient probe input, enumerates the property's items, and removes the probe in a
     * finally. Returns an empty list on any null/unsuccessful response so a half-up OBS never 500s.
     */
    private List<AudioInputOption> enumerate(
            OBSRemoteController c, String obsKind, String propertyName) {
        String probe = PROBE_PREFIX + java.util.UUID.randomUUID();
        boolean created = false;
        try {
            CreateInputResponse create =
                    c.createInput(
                            SCENE_NAME,
                            probe,
                            obsKind,
                            new JsonObject(),
                            false, // sceneItemEnabled=false: hidden helper, never shown
                            REQUEST_TIMEOUT_MS);
            if (create == null || !create.isSuccessful()) {
                log.debug("Could not create probe input for kind '{}'", obsKind);
                return List.of();
            }
            created = true;
            GetInputPropertiesListPropertyItemsResponse resp =
                    c.getInputPropertiesListPropertyItems(
                            probe, propertyName, REQUEST_TIMEOUT_MS);
            if (resp == null || !resp.isSuccessful() || resp.getPropertyItems() == null) {
                return List.of();
            }
            List<AudioInputOption> options = new ArrayList<>();
            for (PropertyItem item : resp.getPropertyItems()) {
                if (item == null) {
                    continue;
                }
                options.add(new AudioInputOption(item.getItemValue(), item.getItemName()));
            }
            return options;
        } finally {
            if (created) {
                try {
                    c.removeInput(probe, REQUEST_TIMEOUT_MS);
                } catch (Exception e) {
                    log.debug("Ignoring error removing probe input: {}", e.toString());
                }
            }
        }
    }

    private static String obsKind(String kind) {
        if (kind == null) {
            return null;
        }
        switch (kind) {
            case "application":
                return "wasapi_process_output_capture";
            case "output":
                return "wasapi_output_capture";
            case "input":
                return "wasapi_input_capture";
            default:
                return null;
        }
    }

    private static String propertyName(String kind) {
        if (kind == null) {
            return null;
        }
        switch (kind) {
            case "application":
                return "window";
            case "output":
            case "input":
                return "device_id";
            default:
                return null;
        }
    }

    /**
     * One selectable audio target. {@code value} is the {@code PropertyItem.getItemValue()} stored
     * into {@code AudioSource.target}; {@code label} is the {@code PropertyItem.getItemName()} shown
     * in the picker.
     */
    public record AudioInputOption(String value, String label) {}
}
