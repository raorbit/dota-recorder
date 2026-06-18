package dev.dotarec.data;

import dev.dotarec.data.MatchRepository.NewMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the exact bucket membership predicates: each {@code ?bucket=} returns precisely its set,
 * un-enriched matches route to Unsorted (never default into Unranked), Turbo/AbilityDraft are carved
 * out of Unranked, and {@code bucketCounts()} agrees row-for-row with the per-bucket list queries.
 */
class MatchRepositoryBucketTest {

    private MatchRepository repo;

    private long rankedId;
    private long unrankedId;
    private long turboUnrankedId;
    private long abilityDraftId;
    private long manualId;
    private long clipId;
    private long pendingMatchId;
    private long failedMatchId;
    private long gsiOnlyId;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        DataSource ds = TestDb.migrated(dir);
        repo = new MatchRepository(ds);

        // Ranked: enriched + lobby_type 7.
        rankedId = repo.insert(seed("match", "enriched", b -> b.lobbyType(7).gameMode(22)));
        // Unranked: enriched, match, lobby != 7, game_mode not turbo/AD.
        unrankedId = repo.insert(seed("match", "enriched", b -> b.lobbyType(0).gameMode(22)));
        // Turbo + unranked-looking (lobby != 7) -> must land in Turbo, NOT Unranked.
        turboUnrankedId = repo.insert(seed("match", "enriched", b -> b.lobbyType(0).gameMode(23)));
        // Ability Draft.
        abilityDraftId = repo.insert(seed("match", "enriched", b -> b.lobbyType(0).gameMode(18)));
        // Manual capture.
        manualId = repo.insert(seed("manual", "gsi_only", b -> b));
        // Clip.
        clipId = repo.insert(seed("clip", "gsi_only", b -> b));
        // Un-enriched matches -> Unsorted only.
        pendingMatchId = repo.insert(seed("match", "pending", b -> b.lobbyType(0).gameMode(22)));
        failedMatchId = repo.insert(seed("match", "failed", b -> b.lobbyType(7).gameMode(22)));
        gsiOnlyId = repo.insert(seed("match", "gsi_only", b -> b.lobbyType(0).gameMode(22)));
    }

    @Test
    void rankedBucketContainsOnlyEnrichedLobby7() {
        assertThat(ids(repo.findMatches("Ranked", null, null, null, null)))
                .containsExactlyInAnyOrder(rankedId);
    }

    @Test
    void unrankedExcludesTurboAbilityDraftAndUnenriched() {
        List<Long> ids = ids(repo.findMatches("Unranked", null, null, null, null));
        assertThat(ids).containsExactlyInAnyOrder(unrankedId);
        // The crux: a Turbo game whose lobby_type isn't 7 must NOT leak into Unranked.
        assertThat(ids).doesNotContain(turboUnrankedId, abilityDraftId,
                pendingMatchId, failedMatchId, gsiOnlyId);
    }

    @Test
    void turboBucketCapturesGameMode23RegardlessOfLobby() {
        assertThat(ids(repo.findMatches("Turbo", null, null, null, null)))
                .containsExactlyInAnyOrder(turboUnrankedId);
    }

    @Test
    void abilityDraftBucketCapturesGameMode18() {
        assertThat(ids(repo.findMatches("AbilityDraft", null, null, null, null)))
                .containsExactlyInAnyOrder(abilityDraftId);
    }

    @Test
    void manualAndClipBucketsByRecordKind() {
        assertThat(ids(repo.findMatches("Manual", null, null, null, null)))
                .containsExactlyInAnyOrder(manualId);
        assertThat(ids(repo.findMatches("Clips", null, null, null, null)))
                .containsExactlyInAnyOrder(clipId);
    }

    @Test
    void unsortedCapturesEveryUnenrichedMatch() {
        assertThat(ids(repo.findMatches("Unsorted", null, null, null, null)))
                .containsExactlyInAnyOrder(pendingMatchId, failedMatchId, gsiOnlyId);
    }

    @Test
    void bucketCountsSumMatchesPerBucketQueries() {
        Map<String, Integer> counts = repo.bucketCounts();
        for (Bucket b : Bucket.values()) {
            int listed = repo.findMatches(b.key(), null, null, null, null).size();
            assertThat(counts.get(b.key()))
                    .as("count for bucket %s must equal its filtered list size", b.key())
                    .isEqualTo(listed);
        }
        // Buckets are not a partition (a Turbo row could also be enriched), so don't assert the
        // grand total equals row count; assert each bucket is internally consistent (above) and
        // that the always-present keys are there.
        assertThat(counts.keySet()).containsExactlyInAnyOrder(
                "Ranked", "Unranked", "Turbo", "AbilityDraft", "Manual", "Clips", "Unsorted");
    }

    @Test
    void unknownBucketReturnsEmptyNotEverything() {
        assertThat(repo.findMatches("NoSuchBucket", null, null, null, null)).isEmpty();
    }

    @Test
    void resultAndQueryFiltersComposeWithBucket() {
        // hero/result filter narrows within a bucket.
        List<MatchSummary> won = repo.findMatches("Ranked", "win", "rubick", null, null);
        assertThat(ids(won)).containsExactlyInAnyOrder(rankedId);
        assertThat(repo.findMatches("Ranked", "loss", null, null, null)).isEmpty();
    }

    private static List<Long> ids(List<MatchSummary> rows) {
        return rows.stream().map(MatchSummary::id).toList();
    }

    private interface Tweak {
        NewMatchBuilder apply(NewMatchBuilder b);
    }

    private NewMatch seed(String recordKind, String enrichmentState, Tweak tweak) {
        NewMatchBuilder b = new NewMatchBuilder()
                .recordKind(recordKind)
                .enrichmentState(enrichmentState)
                .hero("rubick")
                .result("win")
                .playedAt(1_000L);
        return tweak.apply(b).build();
    }

    /** Tiny builder so seed rows read clearly; only the fields these tests vary are exposed. */
    private static final class NewMatchBuilder {
        private String recordKind = "match";
        private String enrichmentState = "pending";
        private String hero;
        private String result;
        private Integer lobbyType;
        private Integer gameMode;
        private Long playedAt;
        private String videoPath;
        private Long fileSizeBytes;
        private boolean starred;

        NewMatchBuilder recordKind(String v) { this.recordKind = v; return this; }
        NewMatchBuilder enrichmentState(String v) { this.enrichmentState = v; return this; }
        NewMatchBuilder hero(String v) { this.hero = v; return this; }
        NewMatchBuilder result(String v) { this.result = v; return this; }
        NewMatchBuilder lobbyType(Integer v) { this.lobbyType = v; return this; }
        NewMatchBuilder gameMode(Integer v) { this.gameMode = v; return this; }
        NewMatchBuilder playedAt(Long v) { this.playedAt = v; return this; }

        NewMatch build() {
            return new NewMatch(
                    null, recordKind, enrichmentState, hero,
                    1, 2, 3, 400, 500, 10000, 120,
                    result, lobbyType, gameMode, null, null, 1800,
                    playedAt, videoPath, null, fileSizeBytes, starred, 1_000L);
        }
    }
}
