package dev.dotarec.data;

/**
 * One row of the {@code markers} table: a timeline annotation pinned to a position in the recorded
 * video. {@code videoOffsetS} is seconds from the start of the .mp4 (the value the player seeks to);
 * {@code gameClock} is the in-game clock at that moment (nullable, e.g. for pre-horn events) and
 * {@code source} records whether the marker came from live GSI or post-match replay enrichment.
 *
 * @param id           surrogate PK
 * @param matchId      owning {@code matches.id}
 * @param type         marker kind (e.g. {@code kill}, {@code death}, {@code teamfight}, {@code roshan})
 * @param videoOffsetS seconds from the start of the video file
 * @param gameClock    in-game clock in seconds, or null when unknown
 * @param label        optional human-readable label
 * @param source       {@code gsi} (live) or {@code replay} (enriched)
 */
public record MarkerRow(
        long id,
        long matchId,
        String type,
        double videoOffsetS,
        Integer gameClock,
        String label,
        String source
) {
}
