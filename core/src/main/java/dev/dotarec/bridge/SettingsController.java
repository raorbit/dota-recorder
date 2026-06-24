package dev.dotarec.bridge;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.config.SettingsStore.Settings;
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

    private final SettingsStore store;

    public SettingsController(SettingsStore store) {
        this.store = store;
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
                    return current;
                });
        return SettingsView.of(store.get());
    }

    /**
     * Read view of settings. The app-managed OBS connection (host/port/password) is intentionally
     * omitted. Null fields are still serialized so the UI sees a stable shape.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SettingsView(
            String resolution, String encoder, int retentionCapGb, String videoDir, Long accountId) {

        static SettingsView of(Settings s) {
            return new SettingsView(
                    s.resolution, s.encoder, s.retentionCapGb, s.videoDir, s.accountId);
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
            Boolean clearAccountId) {}
}
