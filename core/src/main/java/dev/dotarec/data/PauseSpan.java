package dev.dotarec.data;

/**
 * One row of the {@code pauses} table: a span during a match when the game was paused. Both ends are
 * wall-clock epoch millis. {@code endWall} is null while a pause is still open (the game is paused
 * right now); it is set when the pause is closed. The player uses these spans to grey out / skip
 * dead time on the timeline.
 *
 * @param id        surrogate PK
 * @param matchId   owning {@code matches.id}
 * @param startWall epoch millis when the pause began
 * @param endWall   epoch millis when the pause ended, or null while still paused
 */
public record PauseSpan(
        long id,
        long matchId,
        long startWall,
        Long endWall
) {
}
