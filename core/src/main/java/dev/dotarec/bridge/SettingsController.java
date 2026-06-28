package dev.dotarec.bridge;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.config.SettingsStore.AudioSource;
import dev.dotarec.config.SettingsStore.Settings;
import dev.dotarec.obs.ObsController;
import dev.dotarec.obs.setup.ObsConfigWriter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings endpoint consumed by the Electron settings UI over the loopback bridge.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li>{@code GET /settings} -> 200 with {@link SettingsView}. The OBS WebSocket connection
 *       (host/port/password) is app-managed and not part of the user-facing surface, so it is not
 *       exposed here.</li>
 *   <li>{@code PUT /settings} -> 200 with the updated {@link SettingsView}. The body is a
 *       <em>partial</em> update ({@link SettingsPatch}): any field left null is preserved, so the
 *       UI can submit just the fields it changed. The app-managed OBS fields are never touched by
 *       this endpoint.</li>
 * </ul>
 */
@RestController
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final SettingsStore store;
    private final ObsController obsController;
    private final ObsConfigWriter obsConfigWriter;

    public SettingsController(
            SettingsStore store, ObsController obsController, ObsConfigWriter obsConfigWriter) {
        this.store = store;
        this.obsController = obsController;
        this.obsConfigWriter = obsConfigWriter;
    }

    @GetMapping("/settings")
    public SettingsView getSettings() {
        return SettingsView.of(store.get());
    }

    @PutMapping("/settings")
    public SettingsView putSettings(@RequestBody SettingsPatch patch) {
        // Atomic read-copy-mutate: only the four user-facing fields are overlaid (non-null), so the
        // app-managed OBS fields (host/port/password) carry forward untouched rather than being
        // reset to defaults.
        store.update(
                current -> {
                    if (patch.resolution() != null) {
                        current.resolution = patch.resolution();
                    }
                    if (patch.encoder() != null) {
                        current.encoder = patch.encoder();
                    }
                    if (patch.fps() != null) {
                        current.fps = patch.fps();
                    }
                    if (patch.quality() != null) {
                        current.quality = patch.quality();
                    }
                    if (patch.format() != null) {
                        current.format = patch.format();
                    }
                    if (patch.retentionCapGb() != null) {
                        current.retentionCapGb = patch.retentionCapGb();
                    }
                    if (patch.videoDir() != null) {
                        current.videoDir = patch.videoDir();
                    }
                    // accountId also uses null = "leave unchanged", so clearing it needs an explicit
                    // flag (a blanked Account ID field in the UI sends clearAccountId=true).
                    if (Boolean.TRUE.equals(patch.clearAccountId())) {
                        current.accountId = null;
                    } else if (patch.accountId() != null) {
                        current.accountId = patch.accountId();
                    }
                    // audioSources is a FULL-LIST REPLACE: null = leave unchanged, [] = clear all,
                    // [..] = replace the entire stored list. No per-element merge, no clear-flag — the
                    // renderer always sends the complete current array on any edit/add/remove.
                    if (patch.audioSources() != null) {
                        current.audioSources = patch.audioSources();
                    }
                    return current;
                });
        // Apply the (possibly new) audio source list to a live OBS without waiting for a reconnect.
        // Best-effort: the persisted settings are the source of truth and the next disconnect->connect
        // edge re-reconciles, so an OBS-down or transient failure here must never 500 the PUT.
        try {
            obsController.reconcileAudioOnDemand();
        } catch (Exception e) {
            log.debug("On-demand audio reconcile after settings PUT failed (OBS down?): {}", e.toString());
        }
        // Re-write basic.ini from the saved settings so the recording profile (fps/quality/format/
        // encoder/resolution) is fresh for the NEXT OBS launch instead of stale until the next reboot.
        // Best-effort and side-effect-free (no copy/probe/websocket rewrite); the saved settings are the
        // source of truth and boot-time configure() is authoritative, so a failure here must never 500.
        try {
            obsConfigWriter.applyProfile();
        } catch (Exception e) {
            log.debug("Profile re-write after settings PUT failed: {}", e.toString());
        }
        return SettingsView.of(store.get());
    }

    /**
     * Read view of settings. The app-managed OBS connection (host/port/password) is intentionally
     * omitted. Null fields are still serialized so the UI sees a stable shape.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SettingsView(
            String resolution,
            String encoder,
            int retentionCapGb,
            String videoDir,
            Long accountId,
            List<AudioSource> audioSources,
            int fps,
            String quality,
            String format) {

        static SettingsView of(Settings s) {
            return new SettingsView(
                    s.resolution,
                    s.encoder,
                    s.retentionCapGb,
                    s.videoDir,
                    s.accountId,
                    s.audioSources,
                    s.fps,
                    s.quality,
                    s.format);
        }
    }

    /**
     * Partial update body. Every field is nullable; null means "leave unchanged". Wrapper types
     * (not {@code int}) so an omitted {@code retentionCapGb} is distinguishable from an explicit 0.
     * {@code clearAccountId=true} is the explicit "set accountId to null" signal, since a null
     * {@code accountId} (like every other field) means "leave unchanged".
     */
    public record SettingsPatch(
            String resolution,
            String encoder,
            Integer retentionCapGb,
            String videoDir,
            Long accountId,
            Boolean clearAccountId,
            List<AudioSource> audioSources,
            Integer fps,
            String quality,
            String format) {}
}
