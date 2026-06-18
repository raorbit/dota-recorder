package dev.dotarec.setup;

import org.springframework.stereotype.Component;

/**
 * Writes the gamestate_integration .cfg that makes Dota POST GSI to this app.
 *
 * <p>Plan (GSI feed): Dota emits GSI when a cfg exists under
 * {@code <dota>/game/dota/cfg/gamestate_integration/}. The cfg points the URI at
 * http://127.0.0.1:3223/gsi and sets a ~10Hz throttle (so heartbeat/liveness and offset math
 * have sub-second resolution). Should subscribe to map/player/hero blocks.
 *
 * <p>TODO(plan: Setup): resolve the dota dir via {@code SteamPathDiscovery}, render the cfg
 * (uri + throttle ~0.1 + auth token + data blocks), write it, and verify by read-back.
 */
@Component
public class GsiCfgInstaller {

    private final SteamPathDiscovery steamPathDiscovery;

    public GsiCfgInstaller(SteamPathDiscovery steamPathDiscovery) {
        this.steamPathDiscovery = steamPathDiscovery;
    }

    // TODO(plan): write gamestate_integration_dotarec.cfg (uri :3223/gsi, throttle ~0.1).
}
