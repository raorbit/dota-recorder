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
 *   <li>{@code GET /settings} -> 200 with {@link SettingsView}. The OBS WebSocket password is
 *       never echoed back as plaintext; instead {@code obsPasswordSet} reports whether one is
 *       configured so the UI can show "set / not set" without leaking the secret.</li>
 *   <li>{@code PUT /settings} -> 200 with the updated {@link SettingsView}. The body is a
 *       <em>partial</em> update ({@link SettingsPatch}): any field left null is preserved, so the
 *       UI can submit just the fields it changed -- and crucially can omit {@code obsPassword} to
 *       keep the existing one rather than wiping it with the masked GET value.</li>
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
        Settings current = store.get();
        Settings updated = new Settings();
        // Copy current, then overlay only the fields present in the patch (non-null).
        updated.resolution = patch.resolution() != null ? patch.resolution() : current.resolution;
        updated.encoder = patch.encoder() != null ? patch.encoder() : current.encoder;
        updated.retentionCapGb =
                patch.retentionCapGb() != null ? patch.retentionCapGb() : current.retentionCapGb;
        updated.obsHost = patch.obsHost() != null ? patch.obsHost() : current.obsHost;
        updated.obsPort = patch.obsPort() != null ? patch.obsPort() : current.obsPort;
        // Omitting obsPassword preserves the stored secret; sending it (even "") sets it.
        updated.obsPassword =
                patch.obsPassword() != null ? patch.obsPassword() : current.obsPassword;
        updated.videoDir = patch.videoDir() != null ? patch.videoDir() : current.videoDir;
        store.save(updated);
        return SettingsView.of(updated);
    }

    /**
     * Read view of settings. Excludes the raw OBS password; exposes only whether one is set.
     * Null fields are still serialized so the UI sees a stable shape.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SettingsView(
            String resolution,
            String encoder,
            int retentionCapGb,
            String videoDir,
            String obsHost,
            int obsPort,
            boolean obsPasswordSet) {

        static SettingsView of(Settings s) {
            boolean passwordSet = s.obsPassword != null && !s.obsPassword.isBlank();
            return new SettingsView(
                    s.resolution,
                    s.encoder,
                    s.retentionCapGb,
                    s.videoDir,
                    s.obsHost,
                    s.obsPort,
                    passwordSet);
        }
    }

    /**
     * Partial update body. Every field is nullable; null means "leave unchanged". Wrapper types
     * (not {@code int}) so an omitted {@code retentionCapGb}/{@code obsPort} is distinguishable
     * from an explicit 0. {@code obsPassword} is write-only: the UI sends it to set the secret and
     * omits it to keep the existing one (it is never echoed back by {@link SettingsView}).
     */
    public record SettingsPatch(
            String resolution,
            String encoder,
            Integer retentionCapGb,
            String videoDir,
            String obsHost,
            Integer obsPort,
            String obsPassword) {}
}
