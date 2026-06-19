package dev.dotarec.gsi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Raw GSI wire DTO. Dota's GSI JSON is large and version-volatile, so only the nested fields the
 * FSM/tagger actually use are modeled and {@code @JsonIgnoreProperties(ignoreUnknown = true)} keeps
 * this forward-compatible across Dota client versions.
 *
 * <p>Shape is taken from a REAL captured frame ({@code test/resources/gsi/game_in_progress.json}),
 * not from docs. Notable facts baked into the mapping:
 * <ul>
 *   <li>{@code map.matchid} is a STRING (e.g. {@code "0"} in Hero Demo), parsed to a long here.</li>
 *   <li>{@code map.clock_time} is an int that can be negative pre-horn; carried through verbatim.</li>
 *   <li>The {@code player} and {@code hero} blocks are ABSENT on HERO_SELECTION and on heartbeat
 *       pings, so every access below null-guards. {@link #toFrame(long)} treats a missing
 *       {@code game_state} as {@code "UNKNOWN"} so the FSM no-ops rather than NPEs.</li>
 *   <li>The {@code previously} diff block is intentionally NOT modeled -- the tagger diffs whole
 *       {@link GsiFrame}s itself.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GsiPayload {

    public Provider provider;
    public Map map;
    public Player player;
    public Hero hero;

    /**
     * Flattens this wire payload into the normalized {@link GsiFrame} the FSM/tagger consume.
     *
     * @param wallClockMillis local arrival time stamped by the controller at request entry; the
     *                        anchor for video offsets (NEVER {@code game_clock})
     */
    public GsiFrame toFrame(long wallClockMillis) {
        String gameState = (map != null && map.gameState != null) ? map.gameState : "UNKNOWN";
        int gameClock = map != null ? map.clockTime : 0;
        boolean paused = map != null && map.paused;
        long matchId = parseMatchId(map != null ? map.matchid : null);
        int radiantScore = map != null ? map.radiantScore : 0;
        int direScore = map != null ? map.direScore : 0;

        boolean heroPresent = hero != null;
        boolean alive = hero != null && hero.alive;
        String heroName = hero != null ? hero.name : null;
        int heroId = hero != null ? hero.id : 0;

        int kills = player != null ? player.kills : 0;
        int deaths = player != null ? player.deaths : 0;
        int assists = player != null ? player.assists : 0;
        String activity = player != null ? player.activity : null;

        return new GsiFrame(
                wallClockMillis,
                gameState,
                gameClock,
                paused,
                heroPresent,
                alive,
                matchId,
                heroName,
                heroId,
                activity,
                kills,
                deaths,
                assists,
                radiantScore,
                direScore);
    }

    /**
     * Parses {@code map.matchid} (a String like {@code "0"} or a real id) to a long. Blank, absent,
     * or unparseable values map to {@code 0L} -- a private lobby / Hero Demo legitimately reports
     * {@code "0"}, and the FSM must not crash on a non-numeric id from a future client.
     */
    static long parseMatchId(String matchid) {
        if (matchid == null || matchid.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(matchid.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Provider {
        public String name;
        public int appid;
        public int version;
        public long timestamp;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Map {
        public String name;
        public String matchid;
        @JsonProperty("game_time")
        public int gameTime;
        @JsonProperty("clock_time")
        public int clockTime;
        @JsonProperty("game_state")
        public String gameState;
        public boolean paused;
        @JsonProperty("radiant_score")
        public int radiantScore;
        @JsonProperty("dire_score")
        public int direScore;
        @JsonProperty("win_team")
        public String winTeam;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Player {
        public String steamid;
        public String accountid;
        public String name;
        public String activity;
        public int kills;
        public int deaths;
        public int assists;
        @JsonProperty("last_hits")
        public int lastHits;
        @JsonProperty("player_slot")
        public int playerSlot;
        public int gpm;
        public int xpm;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Hero {
        public int id;
        public String name;
        public int level;
        public boolean alive;
        @JsonProperty("respawn_seconds")
        public int respawnSeconds;
        public int health;
        @JsonProperty("max_health")
        public int maxHealth;
    }
}
