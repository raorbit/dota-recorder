package dev.dotarec.setup;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Locates the Dota 2 install so the GSI cfg can be written into its game tree.
 *
 * <p>Discovery order (Windows): HKCU\\Software\\Valve\\Steam SteamPath -> HKLM
 * (WOW6432Node) InstallPath fallback -> parse {@code steamapps/libraryfolders.vdf} for extra
 * library roots -> in each, read {@code steamapps/appmanifest_570.acf} (570 = Dota 2) for the
 * install folder. The GSI cfg goes under {@code <dota>/game/dota/cfg/gamestate_integration/}.
 *
 * <p>TODO(plan: Setup -> GSI): implement the registry + VDF/ACF walk and return the dota dir.
 */
@Component
public class SteamPathDiscovery {

    /** Dota 2 Steam app id. */
    static final String DOTA_APP_ID = "570";

    /** TODO(plan): HKCU SteamPath -> HKLM -> libraryfolders.vdf -> appmanifest_570.acf. */
    public Optional<String> findDotaInstallDir() {
        return Optional.empty();
    }
}
