package dev.dotarec.gsi;

/**
 * Normalized, FSM-facing view of a single GSI sample.
 *
 * <p>This is the flattened shape the FSM and tagger consume, decoupled from the raw
 * {@link GsiPayload} wire DTO. {@code wallClockMillis} is the local arrival time used to anchor
 * video offsets (plan: {@code markers.video_offset_s} "maps a game event to a frame in the
 * recorded .mp4"); {@code gameClock} is kept for display/enrichment only and is never used for
 * offset math (see {@code VideoOffsetCalculator}).
 *
 * <p>Null-safety contract (built from the real fixture + heartbeat samples):
 * <ul>
 *   <li>{@code gameState} is never null -- {@link GsiPayload#toFrame} maps an absent
 *       {@code map.game_state} to {@code "UNKNOWN"} so the FSM no-ops instead of NPEing.</li>
 *   <li>On HERO_SELECTION / heartbeat pings the {@code hero} and {@code player} blocks are ABSENT.
 *       {@code heroPresent} reflects that; {@code alive} is only true when the hero block exists
 *       AND reports alive, so a missing hero never reads as a (phantom) death.</li>
 *   <li>{@code gameClock} is {@code map.clock_time} verbatim and may be negative pre-horn.</li>
 * </ul>
 */
public record GsiFrame(
        long wallClockMillis,
        String gameState,
        int gameClock,
        boolean paused,
        boolean heroPresent,
        boolean alive,
        long matchId,
        String hero,
        int heroId,
        String activity,
        int kills,
        int deaths,
        int assists,
        int radiantScore,
        int direScore) {
}
