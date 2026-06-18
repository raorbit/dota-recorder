package dev.dotarec.data;

import java.util.Optional;

/**
 * Library buckets (left-nav categories) and their exact SQL membership predicates over the
 * {@code matches} table.
 *
 * <p>The predicates are the single source of truth shared by {@link MatchRepository#findMatches}
 * (filtered list) and {@link MatchRepository#bucketCounts} (per-bucket counts) so the two can never
 * disagree. Each {@link #predicate()} is a self-contained boolean SQL fragment safe to drop into a
 * {@code WHERE} clause (no user input, no bind params).
 *
 * <p>Key invariant from the plan: un-enriched matches ({@code enrichment_state} in
 * {@code pending}/{@code failed}/{@code gsi_only}) route to {@link #UNSORTED}, NEVER defaulting into
 * {@link #UNRANKED}. Turbo ({@code game_mode=23}) and Ability Draft ({@code game_mode=18}) are their
 * own buckets and are explicitly carved out of {@link #UNRANKED}.
 */
public enum Bucket {

    RANKED("Ranked",
            "enrichment_state = 'enriched' AND lobby_type = 7"),

    UNRANKED("Unranked",
            "enrichment_state = 'enriched' AND record_kind = 'match' "
                    + "AND (lobby_type IS NULL OR lobby_type <> 7) "
                    + "AND (game_mode IS NULL OR game_mode NOT IN (23, 18))"),

    TURBO("Turbo",
            "game_mode = 23"),

    ABILITY_DRAFT("AbilityDraft",
            "game_mode = 18"),

    MANUAL("Manual",
            "record_kind = 'manual'"),

    CLIPS("Clips",
            "record_kind = 'clip'"),

    UNSORTED("Unsorted",
            "record_kind = 'match' "
                    + "AND enrichment_state IN ('pending', 'failed', 'gsi_only')");

    /** Wire/query identifier used by the {@code ?bucket=} param and {@code /buckets/counts} keys. */
    private final String key;
    private final String predicate;

    Bucket(String key, String predicate) {
        this.key = key;
        this.predicate = predicate;
    }

    public String key() {
        return key;
    }

    /** Self-contained boolean SQL fragment for membership in this bucket. */
    public String predicate() {
        return predicate;
    }

    /** Resolves a {@code ?bucket=} value (case-insensitive) to a bucket, if it names one. */
    public static Optional<Bucket> fromKey(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        for (Bucket b : values()) {
            if (b.key.equalsIgnoreCase(value.trim())) {
                return Optional.of(b);
            }
        }
        return Optional.empty();
    }
}
