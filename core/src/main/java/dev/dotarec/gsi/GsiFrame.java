package dev.dotarec.gsi;

/**
 * Normalized, FSM-facing view of a single GSI sample.
 *
 * <p>This is the flattened shape the FSM and tagger consume, decoupled from the raw
 * {@link GsiPayload} wire DTO. {@code wallClockMillis} is the local arrival time kept for
 * storage/display (journal rows, pause spans). {@code monotonicNanos} is the arrival stamp from
 * {@code System.nanoTime()} used for the video-offset delta (plan: {@code markers.video_offset_s}
 * "maps a game event to a frame in the recorded .mp4") -- a monotonic source so an OS/NTP clock
 * step between the record-confirmed anchor and a frame cannot shift markers. {@code gameClock} is
 * kept for display/enrichment only and is never used for offset math (see
 * {@code VideoOffsetCalculator}).
 *
 * <p>Null-safety contract (built from the real fixture + heartbeat samples):
 * <ul>
 *   <li>{@code gameState} is never null -- {@link GsiPayload#toFrame} maps an absent
 *       {@code map.game_state} to {@code "UNKNOWN"} so the FSM no-ops instead of NPEing.</li>
 *   <li>On HERO_SELECTION / heartbeat pings the {@code hero} and {@code player} blocks are ABSENT.
 *       {@code heroPresent} reflects the hero block; {@code playerPresent} reflects the player block
 *       (the two are independent -- a heartbeat/reconnect can drop the player block while the hero
 *       block stays). {@code alive} is only true when the hero block exists AND reports alive, so a
 *       missing hero never reads as a (phantom) death. {@code playerPresent=false} forces the
 *       kills/deaths/assists counters to their {@code 0} default ({@link GsiPayload#toFrame}); the
 *       tagger gates counter-delta markers on player presence on BOTH frames so a vanished-then-
 *       returned player block can't manufacture phantom kill/death/assist markers.</li>
 *   <li>{@code gameClock} is {@code map.clock_time} verbatim and may be negative pre-horn.</li>
 * </ul>
 */
public record GsiFrame(
        long wallClockMillis,
        long monotonicNanos,
        String gameState,
        int gameClock,
        boolean paused,
        boolean heroPresent,
        boolean alive,
        boolean playerPresent,
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
