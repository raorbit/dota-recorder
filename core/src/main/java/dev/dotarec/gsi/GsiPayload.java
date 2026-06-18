package dev.dotarec.gsi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Raw GSI wire DTO shell.
 *
 * <p>Dota's GSI JSON is large and version-volatile, so unknown properties are ignored to stay
 * forward-compatible. Mapping into {@link GsiFrame} is deferred.
 *
 * <p>TODO(plan: GSI feed): model the nested blocks actually used
 * (map.game_state, map.clock_time, map.paused, map.matchid; player.kills/deaths/assists;
 * hero.alive/level; hero presence), then add {@code toFrame(long wallClockMillis)}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GsiPayload {
    // TODO(plan): nested provider/map/player/hero blocks.
}
