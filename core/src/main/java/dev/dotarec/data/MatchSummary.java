package dev.dotarec.data;

/**
 * One row of the matches list as the browse UI consumes it. Mirrors the table columns the
 * mockup renders (hero, result, K/D/A, GPM, mode, MMR, date) plus identity and video linkage.
 *
 * <p>TODO: extend as later steps wire enrichment + filtering (plan storage model).
 */
public record MatchSummary(
        long id,
        Long dotaMatchId,
        String recordKind,
        String enrichmentState,
        String hero,
        Integer kills,
        Integer deaths,
        Integer assists,
        Integer gpm,
        Integer xpm,
        Integer netWorth,
        Integer lastHits,
        String result,
        Integer lobbyType,
        Integer gameMode,
        Integer rankTier,
        Integer mmrDelta,
        Integer durationS,
        Long playedAt,
        String videoPath,
        String thumbPath,
        Long fileSizeBytes,
        boolean starred,
        long createdAt,
        Long recordStartedWallMs
) {
}
