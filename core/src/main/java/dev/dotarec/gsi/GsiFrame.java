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
 * <p>TODO(plan: Storage model / Event detection): fields will be populated by mapping from
 * {@link GsiPayload}; add validation once parsing lands.
 */
public record GsiFrame(
        long wallClockMillis,
        String gameState,
        int gameClock,
        boolean alive,
        int kills,
        int deaths,
        int assists,
        boolean paused,
        boolean heroPresent,
        long matchId,
        String hero) {
}
