package dev.dotarec.enrich;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Jackson DTO for the OpenDota {@code /matches/{id}} response, trimmed to the fields the enricher
 * consumes. Component names match the OpenDota JSON keys verbatim (snake_case) so the default
 * mapper binds them without {@code @JsonProperty}. Unknown keys are ignored, and every numeric
 * field is boxed so an unparsed (NotReady) response maps cleanly to nulls.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenDotaMatch(
        Long match_id,
        Boolean radiant_win,
        Integer duration,
        Long start_time,
        Integer lobby_type,
        Integer game_mode,
        List<Player> players) {

    /** One row of the 10-player scoreboard. Boxed numerics so partial/anonymized rows map cleanly. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Player(
            Long account_id,
            Integer player_slot,
            Integer hero_id,
            Integer kills,
            Integer deaths,
            Integer assists,
            Integer gold_per_min,
            Integer xp_per_min,
            Integer net_worth,
            Integer last_hits,
            Integer rank_tier) {
    }
}
