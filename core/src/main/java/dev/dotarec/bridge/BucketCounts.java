package dev.dotarec.bridge;

import dev.dotarec.data.Bucket;

import java.util.Map;

/**
 * Wire shape of {@code GET /buckets/counts}: one count per library bucket. Every bucket is always
 * present (0 when empty) so the renderer can render a stable left-nav with no null checks.
 *
 * <p>Field names match {@link Bucket#key()} keys: {@code Ranked}, {@code Unranked}, {@code Turbo},
 * {@code AbilityDraft}, {@code Manual}, {@code Clips}, {@code Unsorted}.
 */
public record BucketCounts(
        int ranked,
        int unranked,
        int turbo,
        int abilityDraft,
        int manual,
        int clips,
        int unsorted) {

    /** Builds the typed view from the repository's keyed counts, defaulting any missing key to 0. */
    public static BucketCounts of(Map<String, Integer> counts) {
        return new BucketCounts(
                counts.getOrDefault(Bucket.RANKED.key(), 0),
                counts.getOrDefault(Bucket.UNRANKED.key(), 0),
                counts.getOrDefault(Bucket.TURBO.key(), 0),
                counts.getOrDefault(Bucket.ABILITY_DRAFT.key(), 0),
                counts.getOrDefault(Bucket.MANUAL.key(), 0),
                counts.getOrDefault(Bucket.CLIPS.key(), 0),
                counts.getOrDefault(Bucket.UNSORTED.key(), 0));
    }
}
