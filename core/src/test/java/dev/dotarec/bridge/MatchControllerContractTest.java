package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.data.MarkerRepository;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.NewMatch;
import dev.dotarec.data.PauseRepository;
import dev.dotarec.data.TestDb;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks the bridge JSON contract consumed by the renderer. The UI must receive the real
 * {@link dev.dotarec.data.MatchSummary} row shape (surrogate {@code id}, nullable numeric
 * {@code playedAt}), not the obsolete mockup list shape ({@code matchId}/{@code category}).
 */
class MatchControllerContractTest {

    private static final String[] MATCH_FIELDS = {
        "id",
        "dotaMatchId",
        "recordKind",
        "enrichmentState",
        "hero",
        "kills",
        "deaths",
        "assists",
        "gpm",
        "xpm",
        "netWorth",
        "lastHits",
        "result",
        "lobbyType",
        "gameMode",
        "rankTier",
        "mmrDelta",
        "durationS",
        "playedAt",
        "videoPath",
        "thumbPath",
        "fileSizeBytes",
        "starred",
        "createdAt",
        "recordStartedWallMs"
    };

    private final ObjectMapper mapper = new ObjectMapper();

    private MatchRepository repo;
    private MatchController controller;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        DataSource ds = TestDb.migrated(dir);
        repo = new MatchRepository(ds);
        controller = new MatchController(repo, new MarkerRepository(ds), new PauseRepository(ds));
    }

    @Test
    void matches_jsonUsesCanonicalMatchSummaryShape() {
        long id =
                repo.insert(
                        new NewMatch(
                                7_894L,
                                "match",
                                "enriched",
                                "rubick",
                                5,
                                2,
                                12,
                                412,
                                588,
                                11_000,
                                129,
                                "win",
                                7,
                                22,
                                54,
                                null,
                                2_344,
                                1_719_000_000_000L,
                                "D:/vods/rubick.mp4",
                                "D:/vods/rubick.jpg",
                                123_456_789L,
                                false,
                                1_719_000_001_000L,
                                1_718_999_999_000L));

        JsonNode row = mapper.valueToTree(controller.matches(null, null, null, null, null).get(0));

        assertThat(row.fieldNames()).toIterable().containsExactlyInAnyOrder(MATCH_FIELDS);
        assertThat(row.has("matchId")).isFalse();
        assertThat(row.has("category")).isFalse();
        assertThat(row.get("id").isIntegralNumber()).isTrue();
        assertThat(row.get("id").asLong()).isEqualTo(id);
        assertThat(row.get("playedAt").isIntegralNumber()).isTrue();
        assertThat(row.get("playedAt").asLong()).isEqualTo(1_719_000_000_000L);
    }

    @Test
    void matches_jsonAllowsNullPlayedAt() {
        repo.insert(
                new NewMatch(
                        null,
                        "match",
                        "pending",
                        "rubick",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        1_000L,
                        null));

        JsonNode row = mapper.valueToTree(controller.matches(null, null, null, null, null).get(0));

        assertThat(row.fieldNames()).toIterable().containsExactlyInAnyOrder(MATCH_FIELDS);
        assertThat(row.get("playedAt").isNull()).isTrue();
    }
}
