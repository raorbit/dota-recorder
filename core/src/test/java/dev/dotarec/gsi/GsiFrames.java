package dev.dotarec.gsi;

/**
 * Test-only builder for {@link GsiFrame}. The record has many components; this keeps synthetic
 * frame construction in the FSM/tagger tests readable and lets each test vary only what it cares
 * about. Defaults model a normal in-progress frame with a live hero.
 */
public final class GsiFrames {

    private long wallClockMillis = 0L;
    private long monotonicNanos = 0L;
    private String gameState = "DOTA_GAMERULES_STATE_GAME_IN_PROGRESS";
    private int gameClock = 0;
    private boolean paused = false;
    private boolean heroPresent = true;
    private boolean alive = true;
    private boolean playerPresent = true;
    private long matchId = 0L;
    private String hero = "npc_dota_hero_drow_ranger";
    private int heroId = 6;
    private String activity = "playing";
    private int kills = 0;
    private int deaths = 0;
    private int assists = 0;
    private int radiantScore = 0;
    private int direScore = 0;

    public static GsiFrames frame() {
        return new GsiFrames();
    }

    public GsiFrames wall(long v) { this.wallClockMillis = v; return this; }

    /** The monotonic ({@code System.nanoTime()}) arrival stamp the video-offset delta uses. */
    public GsiFrames mono(long v) { this.monotonicNanos = v; return this; }

    public GsiFrames state(String v) { this.gameState = v; return this; }
    public GsiFrames clock(int v) { this.gameClock = v; return this; }
    public GsiFrames paused(boolean v) { this.paused = v; return this; }
    public GsiFrames heroPresent(boolean v) { this.heroPresent = v; return this; }
    public GsiFrames alive(boolean v) { this.alive = v; return this; }
    public GsiFrames playerPresent(boolean v) { this.playerPresent = v; return this; }
    public GsiFrames matchId(long v) { this.matchId = v; return this; }
    public GsiFrames hero(String v) { this.hero = v; return this; }
    public GsiFrames activity(String v) { this.activity = v; return this; }
    public GsiFrames kills(int v) { this.kills = v; return this; }
    public GsiFrames deaths(int v) { this.deaths = v; return this; }
    public GsiFrames assists(int v) { this.assists = v; return this; }

    /** Models a heartbeat / hero-select frame with no hero block. */
    public GsiFrames noHero() {
        this.heroPresent = false;
        this.alive = false;
        this.hero = null;
        return this;
    }

    /**
     * Models a heartbeat / reconnect frame with no PLAYER block: presence flips off and the KDA
     * counters zero out (mirrors {@link GsiPayload#toFrame} defaulting them to 0 when player==null).
     * Distinct from {@link #noHero()} -- the two blocks drop independently.
     */
    public GsiFrames noPlayer() {
        this.playerPresent = false;
        this.kills = 0;
        this.deaths = 0;
        this.assists = 0;
        return this;
    }

    public GsiFrame build() {
        return new GsiFrame(
                wallClockMillis, monotonicNanos, gameState, gameClock, paused, heroPresent, alive,
                playerPresent, matchId, hero, heroId, activity, kills, deaths, assists, radiantScore,
                direScore);
    }
}
