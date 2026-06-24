package dev.dotarec.setup;

import dev.dotarec.config.SettingsStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the gamestate_integration .cfg that makes Dota POST GSI to this app.
 *
 * <p>Dota emits GSI when a cfg exists under {@code <dota>/game/dota/cfg/gamestate_integration/}. The
 * cfg points the URI at {@code http://127.0.0.1:3223/gsi}, sets a ~10Hz throttle (sub-second
 * liveness + offset resolution), subscribes to the provider/map/player/hero blocks the
 * {@link dev.dotarec.gsi.GsiPayload} consumes, and carries an {@code auth { token }} secret the
 * {@link dev.dotarec.gsi.GsiController} validates so a spoofed local POST cannot drive the FSM.
 *
 * <p>{@link #renderCfg(String)} is the single source for both the auto-install write and the manual
 * fallback, so the two can never drift. The token is minted (via {@link SecureRandom}) and persisted
 * to {@code settings.json} only on first install — never eagerly — so a token only ever exists
 * alongside a cfg that carries it.
 */
@Component
public class GsiCfgInstaller {

    private static final Logger log = LoggerFactory.getLogger(GsiCfgInstaller.class);

    /** The endpoint Dota POSTs GSI to: the GSI connector on loopback :3223 (see {@code GsiController}). */
    static final String GSI_URI = "http://127.0.0.1:3223/gsi";

    /** Our cfg filename; the {@code dotarec} suffix keeps it distinct from other tools' GSI cfgs. */
    static final String CFG_FILE_NAME = "gamestate_integration_dotarec.cfg";

    /** Relative to the Dota install root: where Dota loads gamestate_integration cfgs from. */
    static final String CFG_SUBDIR = "game/dota/cfg/gamestate_integration";

    private final SteamPathDiscovery steamPathDiscovery;
    private final SettingsStore settings;

    public GsiCfgInstaller(SteamPathDiscovery steamPathDiscovery, SettingsStore settings) {
        this.steamPathDiscovery = steamPathDiscovery;
        this.settings = settings;
    }

    /** Auto-install outcome. {@code installed=false} with null paths means Dota was not discovered. */
    public record InstallResult(boolean installed, String dotaDir, String cfgPath) {}

    /** Manual-install payload: the cfg body + filename and, if known, the directory to place it in. */
    public record ManualInstructions(String cfgFileName, String cfgBody, String targetDir) {}

    /**
     * Discovers the Dota install, mints+persists the GSI auth token on first run, writes the cfg, and
     * verifies it by read-back. Returns {@code installed=false} (so the endpoint can answer 200) when
     * Dota cannot be found; throws only on a real write failure (permissions / disk).
     */
    public InstallResult install() {
        Optional<String> dota = steamPathDiscovery.findDotaInstallDir();
        if (dota.isEmpty()) {
            return new InstallResult(false, null, null);
        }
        // Reuse an already-persisted token if present; otherwise mint a fresh one but hold it in
        // memory and only persist after the cfg is safely written, so a failed write never leaves
        // settings.json requiring a token that no cfg carries (the invariant documented above).
        String token = existingToken().orElseGet(GsiCfgInstaller::generateToken);
        Path cfgFile = cfgFile(Path.of(dota.get()));
        String body = renderCfg(token);
        try {
            Files.createDirectories(cfgFile.getParent());
            Files.writeString(cfgFile, body, StandardCharsets.UTF_8);
            String readBack = Files.readString(cfgFile, StandardCharsets.UTF_8);
            if (!body.equals(readBack)) {
                throw new IOException("cfg read-back did not match what was written");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write GSI cfg: " + cfgFile, e);
        }
        persistToken(token);
        log.info("Installed GSI cfg at {}", cfgFile);
        return new InstallResult(true, dota.get(), cfgFile.toString());
    }

    /**
     * The cfg body (byte-identical to what {@link #install()} writes) plus its filename and, if Dota is
     * discoverable, the directory to drop it in — for the manual fallback. Mints the token if needed so
     * the manual cfg carries the same secret the controller validates.
     */
    public ManualInstructions manualInstructions() {
        String token = mintTokenIfNeeded();
        String targetDir =
                steamPathDiscovery
                        .findDotaInstallDir()
                        .map(d -> cfgFile(Path.of(d)).getParent().toString())
                        .orElse(null);
        return new ManualInstructions(CFG_FILE_NAME, renderCfg(token), targetDir);
    }

    private static Path cfgFile(Path dotaDir) {
        return dotaDir.resolve(CFG_SUBDIR).resolve(CFG_FILE_NAME);
    }

    /** The persisted GSI token, if one has already been generated. */
    private Optional<String> existingToken() {
        String existing = settings.get().gsiAuthToken;
        return (existing != null && !existing.isBlank()) ? Optional.of(existing) : Optional.empty();
    }

    /** Persists {@code token} as the GSI token if none is set yet (idempotent; never overwrites). */
    private void persistToken(String token) {
        settings.update(
                s -> {
                    if (s.gsiAuthToken == null || s.gsiAuthToken.isBlank()) {
                        s.gsiAuthToken = token;
                    }
                    return s;
                });
    }

    /**
     * Returns the persisted GSI token, generating + saving one the first time it is needed. Used by
     * the manual-install path, where the user places the cfg themselves so the token is persisted
     * up-front rather than gated on a write this app performs.
     */
    private String mintTokenIfNeeded() {
        String token = existingToken().orElseGet(GsiCfgInstaller::generateToken);
        persistToken(token);
        return settings.get().gsiAuthToken;
    }

    /** 24-char URL-safe token (144 bits) — mirrors the obs-websocket password idiom (ObsConfigWriter). */
    private static String generateToken() {
        byte[] buf = new byte[18];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /**
     * Renders the gamestate_integration cfg (Valve KeyValues). Single source for auto + manual installs
     * so the two can never drift.
     */
    String renderCfg(String token) {
        return "\"dota-recorder\"\n"
                + "{\n"
                + "    \"uri\"           \"" + GSI_URI + "\"\n"
                + "    \"timeout\"       \"5.0\"\n"
                + "    \"buffer\"        \"0.1\"\n"
                + "    \"throttle\"      \"0.1\"\n"
                + "    \"heartbeat\"     \"10.0\"\n"
                + "    \"auth\"\n"
                + "    {\n"
                + "        \"token\"     \"" + token + "\"\n"
                + "    }\n"
                + "    \"data\"\n"
                + "    {\n"
                + "        \"provider\"  \"1\"\n"
                + "        \"map\"       \"1\"\n"
                + "        \"player\"    \"1\"\n"
                + "        \"hero\"      \"1\"\n"
                + "    }\n"
                + "}\n";
    }
}
