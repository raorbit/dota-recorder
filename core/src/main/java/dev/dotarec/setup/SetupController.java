package dev.dotarec.setup;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Setup endpoints the UI calls to wire up GSI (discover Dota, install the cfg, get launch option).
 *
 * <p>Plan (Setup / Game State Integration settings): guide the user through enabling GSI --
 * discover the Dota install, install the cfg automatically (or provide manual instructions), and
 * show the {@code -gamestateintegration} launch option.
 *
 * <p>TODO(plan): back these with {@code SteamPathDiscovery} + {@code GsiCfgInstaller} and return
 * real result payloads.
 */
@RestController
public class SetupController {

    private final SteamPathDiscovery steamPathDiscovery;
    private final GsiCfgInstaller gsiCfgInstaller;

    public SetupController(SteamPathDiscovery steamPathDiscovery, GsiCfgInstaller gsiCfgInstaller) {
        this.steamPathDiscovery = steamPathDiscovery;
        this.gsiCfgInstaller = gsiCfgInstaller;
    }

    /** TODO(plan): run discovery, return the resolved Dota install dir. */
    @PostMapping("/setup/gsi/discover")
    public ResponseEntity<Void> discover() {
        return ResponseEntity.ok().build();
    }

    /** TODO(plan): auto-install the GSI cfg into the discovered Dota tree. */
    @PostMapping("/setup/gsi/install")
    public ResponseEntity<Void> install() {
        return ResponseEntity.ok().build();
    }

    /** TODO(plan): return manual cfg contents + target path for the user to place by hand. */
    @PostMapping("/setup/gsi/install-manual")
    public ResponseEntity<Void> installManual() {
        return ResponseEntity.ok().build();
    }

    /** Returns the Steam launch option that activates GSI. */
    @GetMapping("/setup/launch-option")
    public String launchOption() {
        return LaunchOptionHelper.launchOption();
    }
}
