package dev.dotarec.setup;

import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Setup endpoints the UI calls to wire up GSI: discover the Dota install, auto-install the cfg (or
 * fetch manual instructions), and surface the {@code -gamestateintegration} launch option.
 *
 * <p>Discovery/install never 4xx: a Dota install that can't be found comes back as
 * {@code found=false} / {@code installed=false} so the UI can branch to the manual flow cleanly,
 * rather than treating "Dota not installed" as a server error.
 */
@RestController
public class SetupController {

    private final SteamPathDiscovery steamPathDiscovery;
    private final GsiCfgInstaller gsiCfgInstaller;

    public SetupController(SteamPathDiscovery steamPathDiscovery, GsiCfgInstaller gsiCfgInstaller) {
        this.steamPathDiscovery = steamPathDiscovery;
        this.gsiCfgInstaller = gsiCfgInstaller;
    }

    /** Discovery outcome: whether a Dota install was located and where. */
    public record DiscoverResult(boolean found, String dotaDir) {}

    /** Locates the Dota install (registry + libraryfolders walk). */
    @PostMapping("/setup/gsi/discover")
    public DiscoverResult discover() {
        Optional<String> dota = steamPathDiscovery.findDotaInstallDir();
        return new DiscoverResult(dota.isPresent(), dota.orElse(null));
    }

    /** Auto-installs the GSI cfg into the discovered Dota tree (mints the auth token on first run). */
    @PostMapping("/setup/gsi/install")
    public GsiCfgInstaller.InstallResult install() {
        return gsiCfgInstaller.install();
    }

    /** Returns the cfg body + target path so the user can place it by hand when auto-install can't. */
    @PostMapping("/setup/gsi/install-manual")
    public GsiCfgInstaller.ManualInstructions installManual() {
        return gsiCfgInstaller.manualInstructions();
    }

    /** Returns the Steam launch option that activates GSI. */
    @GetMapping("/setup/launch-option")
    public String launchOption() {
        return LaunchOptionHelper.launchOption();
    }
}
